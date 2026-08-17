/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.campaign

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import performance.capture.CaptureOutcome
import performance.capture.CaptureRequest
import performance.capture.CaptureRunner
import performance.compare.RegressionPolicy
import performance.distribution.VerifiedDistribution
import performance.model.CaptureIdentity

data class CampaignRequest(
  val baseline: VerifiedDistribution,
  val candidate: VerifiedDistribution,
  val profileFamily: ProfileFamily,
  val session: SessionIdentity,
  val provisionalRoot: Path,
  val regressionPolicy: RegressionPolicy?,
)

internal fun interface CampaignCapturePort {
  fun capture(role: CaptureRole, request: CaptureRequest): CaptureOutcome
}

/** Single-use frozen A/A escalation and selected A2/B acquisition state machine. */
class CampaignRunner private constructor(
  private val capturePort: CampaignCapturePort,
  private val calibrationEvaluator: ProvisionalCalibrationEvaluator,
  private val rolePreconditioner: RolePreconditioner,
  private val clock: Clock,
) {
  private val started = AtomicBoolean()

  constructor(
    captureRunner: CaptureRunner,
    calibrationEvaluator: ProvisionalCalibrationEvaluator,
    rolePreconditioner: RolePreconditioner,
    clock: Clock,
  ) : this(
    capturePort = CampaignCapturePort { _, request -> captureRunner.capture(request) },
    calibrationEvaluator = calibrationEvaluator,
    rolePreconditioner = rolePreconditioner,
    clock = clock,
  )

  fun run(request: CampaignRequest): CampaignProvisionalOutcome {
    check(started.compareAndSet(false, true)) { "CampaignRunner is single-use" }
    requireValidRoot(request.provisionalRoot)
    val startedAt = clock.instant()
    val captures = mutableListOf<CampaignCapture>()
    val receipts = mutableListOf<PreconditioningReceipt>()
    var captureSequence = 0
    var receiptSequence = 0L

    fun invalid(reason: CampaignFailure): CampaignProvisionalOutcome =
      CampaignProvisionalOutcome(
        status = CampaignStatus.INVALID,
        captures = captures.toList(),
        preconditioningReceipts = receipts.toList(),
        reason = reason,
      )

    fun capture(
      role: CaptureRole,
      forks: Int,
      attemptId: String,
      distribution: VerifiedDistribution,
    ): AttemptCapture {
      if (durationExceeded(startedAt, request.profileFamily.maximumSessionDuration)) {
        return AttemptCapture.Invalid(CampaignFailure.SESSION_DURATION_EXCEEDED)
      }
      val receipt =
        runCatching { rolePreconditioner.prepare(role, distribution) }
          .getOrElse { return AttemptCapture.Invalid(CampaignFailure.PRECONDITION_FAILED) }
      receiptSequence += 1L
      if (
        !CampaignReceiptValidator.validate(
          receipt = receipt,
          expectedRole = role,
          distribution = distribution,
          expectedSettleDuration = request.profileFamily.settleDuration,
          expectedSequence = receiptSequence,
        )
      ) {
        return AttemptCapture.Invalid(CampaignFailure.RECEIPT_INVALID)
      }
      receipts += receipt
      if (durationExceeded(startedAt, request.profileFamily.maximumSessionDuration)) {
        return AttemptCapture.Invalid(CampaignFailure.SESSION_DURATION_EXCEEDED)
      }
      val profile = request.profileFamily.profile(role, forks)
      captureSequence += 1
      val identity = identity(request.session, attemptId, role, captureSequence)
      val operationRoot =
        request.provisionalRoot.resolve("$attemptId-${role.shortName.lowercase()}")
      val captureRequest =
        CaptureRequest(
          distribution = distribution,
          profile = profile,
          identity = identity,
          provisionalRoot = operationRoot,
        )
      val outcome =
        runCatching { capturePort.capture(role, captureRequest) }
          .getOrElse { CaptureOutcome.Invalid(emptyList()) }
      val recorded =
        CampaignCapture(
          attemptId = attemptId,
          role = role,
          forks = forks,
          identity = identity,
          operationRoot = operationRoot,
          preconditioningReceipt = receipt,
          outcome = outcome,
          selected = false,
        )
      captures += recorded
      if (outcome !is CaptureOutcome.Provisional) {
        return AttemptCapture.Invalid(CampaignFailure.CAPTURE_INVALID)
      }
      val validated =
        runCatching {
            require(outcome.document.identity == identity)
            ValidatedProvisionalCapture.verify(
              role = role,
              root = operationRoot,
              document = outcome.document,
              expectedProfile = profile,
              distribution = distribution,
            )
          }
          .getOrElse { return AttemptCapture.Invalid(CampaignFailure.CAPTURE_CONTAMINATED) }
      val outerDurationExceeded =
        durationExceeded(startedAt, request.profileFamily.maximumSessionDuration)
      if (
        outerDurationExceeded ||
          captureDurationExceeded(
            startedAt = startedAt,
            maximum = request.profileFamily.maximumSessionDuration,
            outcome = outcome,
          )
      ) {
        return AttemptCapture.Invalid(CampaignFailure.SESSION_DURATION_EXCEEDED)
      }
      return AttemptCapture.Valid(recorded, validated)
    }

    request.profileFamily.forkLadder.forEachIndexed { index, forks ->
      val attemptId = "${request.profileFamily.id}-$forks-${index + 1}"
      val a1 =
        when (
          val result =
            capture(
              role = CaptureRole.BASELINE_A1,
              forks = forks,
              attemptId = attemptId,
              distribution = request.baseline,
            )
        ) {
          is AttemptCapture.Invalid -> return invalid(result.reason)
          is AttemptCapture.Valid -> result
        }
      val a2 =
        when (
          val result =
            capture(
              role = CaptureRole.BASELINE_A2,
              forks = forks,
              attemptId = attemptId,
              distribution = request.baseline,
            )
        ) {
          is AttemptCapture.Invalid -> return invalid(result.reason)
          is AttemptCapture.Valid -> result
        }
      val calibration =
        runCatching { calibrationEvaluator.evaluate(a1.validated, a2.validated) }
          .getOrElse { return invalid(CampaignFailure.CALIBRATION_INVALID) }
      if (!calibration.passed) return@forEachIndexed

      val selectedA1 = a1.recorded.copy(selected = true)
      val selectedA2 = a2.recorded.copy(selected = true)
      replace(captures, selectedA1)
      replace(captures, selectedA2)
      val candidate =
        when (
          val result =
            capture(
              role = CaptureRole.CANDIDATE_B,
              forks = forks,
              attemptId = attemptId,
              distribution = request.candidate,
            )
        ) {
          is AttemptCapture.Invalid -> return invalid(result.reason)
          is AttemptCapture.Valid -> result.recorded.copy(selected = true)
        }
      replace(captures, candidate)
      return CampaignProvisionalOutcome(
        status = CampaignStatus.QUALIFIED,
        captures = captures.toList(),
        preconditioningReceipts = receipts.toList(),
        selectedA1 = selectedA1,
        selectedA2 = selectedA2,
        candidate = candidate,
        comparisonSelection =
          ProvisionalCandidateSelection(
            baselineA2CaptureId = selectedA2.identity.captureId,
            candidateBCaptureId = candidate.identity.captureId,
          ),
      )
    }
    return CampaignProvisionalOutcome(
      status = CampaignStatus.CALIBRATION_EXHAUSTED,
      captures = captures.toList(),
      preconditioningReceipts = receipts.toList(),
      reason = CampaignFailure.CALIBRATION_EXHAUSTED,
    )
  }

  private fun durationExceeded(startedAt: java.time.Instant, maximum: Duration): Boolean =
    Duration.between(startedAt, clock.instant()) > maximum

  private fun captureDurationExceeded(
    startedAt: Instant,
    maximum: Duration,
    outcome: CaptureOutcome.Provisional,
  ): Boolean {
    val captureStarted = Instant.parse(outcome.document.outcome.startedAtUtc)
    val captureCompleted = Instant.parse(outcome.document.outcome.completedAtUtc)
    val deadline = startedAt.plus(maximum)
    return captureStarted.isBefore(startedAt) ||
      captureStarted.isAfter(deadline) ||
      captureCompleted.isAfter(deadline)
  }

  private fun requireValidRoot(root: Path) {
    require(root.isAbsolute)
    require(root == root.toAbsolutePath().normalize())
    require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root))
  }

  private fun identity(
    session: SessionIdentity,
    attemptId: String,
    role: CaptureRole,
    sequence: Int,
  ): CaptureIdentity {
    val prefix = "${session.campaignId}-$attemptId-${role.shortName.lowercase()}-$sequence"
    return CaptureIdentity(
      captureId = prefix,
      processRunId = "$prefix-process",
      performanceSessionId = session.performanceSessionId,
      sessionSequence = sequence,
    )
  }

  private fun replace(captures: MutableList<CampaignCapture>, selected: CampaignCapture) {
    val index = captures.indexOfFirst { capture -> capture.identity == selected.identity }
    check(index >= 0)
    captures[index] = selected
  }

  companion object {
    internal fun forTesting(
      capturePort: CampaignCapturePort,
      calibrationEvaluator: ProvisionalCalibrationEvaluator,
      rolePreconditioner: RolePreconditioner,
      clock: Clock,
    ): CampaignRunner =
      CampaignRunner(capturePort, calibrationEvaluator, rolePreconditioner, clock)
  }
}

private sealed interface AttemptCapture {
  data class Valid(
    val recorded: CampaignCapture,
    val validated: ValidatedProvisionalCapture,
  ) : AttemptCapture

  data class Invalid(val reason: CampaignFailure) : AttemptCapture
}
