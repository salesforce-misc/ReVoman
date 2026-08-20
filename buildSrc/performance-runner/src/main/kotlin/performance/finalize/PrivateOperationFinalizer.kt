/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.finalize

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator
import performance.campaign.CaptureRole
import performance.campaign.ReceiptFileFact
import performance.compare.RegressionPolicy
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.ProvisionalCaptureDocument
import performance.model.QualificationEvidence
import performance.runner.PrivateOperationWriter
import performance.runner.RunnerExit
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** Consumes runner-owned private state plus Task 10 host documents and invokes the Task 9 sealers. */
internal object PrivateOperationFinalizer {
  fun diagnostic(
    operationRoot: Path,
    stateRoot: Path,
    qualificationRoot: Path,
    artifactParent: Path,
    runToken: String,
    terminal: RunnerExit,
  ): FinalizationOutcome {
    if (terminal != RunnerExit.SUCCESS) return finalizeFailure(artifactParent, runToken, terminal)
    val temp = Files.createTempDirectory("revoman-diagnostic-finalizer-").toRealPath()
    return try {
      val provisional =
        CaptureDocumentCodec.decode(readCanonical(stateRoot.resolve(PrivateOperationWriter.CAPTURE_STATE)))
      val qualification =
        HostQualificationReader.read(
          root = qualificationRoot,
          policy = provisional.protocol.qualificationPolicySha256,
          substrate = provisional.runtime.substrate,
          campaign = false,
        )
      val sealed = temp.resolve("sealed-capture")
      when (DiagnosticCaptureSealer.seal(provisional, operationRoot, sealed, qualification)) {
        is DiagnosticSealOutcome.Rejected ->
          finalizeFailure(artifactParent, runToken, RunnerExit.INPUT_OR_PREFLIGHT_INVALID)
        is DiagnosticSealOutcome.Sealed ->
          EvidenceFinalizer.system()
            .finalizeDiagnostic(
              DiagnosticFinalizationRequest(sealed, artifactParent, runToken, RunnerExit.SUCCESS),
            )
      }
    } catch (_: Exception) {
      finalizeFailure(artifactParent, runToken, RunnerExit.INPUT_OR_PREFLIGHT_INVALID)
    } finally {
      cleanup(temp)
    }
  }

  fun campaign(
    stateRoot: Path,
    qualificationRoot: Path,
    artifactParent: Path,
    runToken: String,
    terminal: RunnerExit,
  ): FinalizationOutcome {
    if (terminal != RunnerExit.SUCCESS) return finalizeFailure(artifactParent, runToken, terminal)
    val temp = Files.createTempDirectory("revoman-campaign-finalizer-").toRealPath()
    return try {
      val state = campaignState(stateRoot)
      val documents =
        state.captures.associateWith { capture ->
          CaptureDocumentCodec.decode(readCanonical(stateRoot.resolve(capture.documentFile)))
        }
      val first = documents.values.first()
      val campaignQualification = campaignQualification(qualificationRoot, first)
      val diagnosticQualification =
        QualificationEvidence.ControlledMacBoundedDiagnostic(
          campaignQualification.policyHash,
          campaignQualification.preflight,
          campaignQualification.watcher,
          campaignQualification.postflight,
          campaignQualification.restoration,
          "standaloneBoundedDiagnostic",
        )
      val sealed =
        state.captures.mapIndexed { index, capture ->
          val target = temp.resolve("sealed-${index + 1}")
          val outcome =
            DiagnosticCaptureSealer.seal(
              documents.getValue(capture),
              capture.operationRoot,
              target,
              diagnosticQualification,
            )
          require(outcome is DiagnosticSealOutcome.Sealed)
          capture to target
        }.toMap()
      val attempts =
        state.captures.groupBy(PrivateCaptureState::attemptId).map { (attemptId, captures) ->
          val byRole = captures.associateBy(PrivateCaptureState::role)
          val a1 = byRole.getValue(CaptureRole.BASELINE_A1)
          val a2 = byRole.getValue(CaptureRole.BASELINE_A2)
          val b = byRole[CaptureRole.CANDIDATE_B]
          CampaignAttemptInput(
            attemptId = attemptId,
            forks = a1.forks,
            a1 = a1.input(sealed.getValue(a1)),
            a2 = a2.input(sealed.getValue(a2)),
            b = b?.input(sealed.getValue(b)),
          )
        }
      val output = temp.resolve("campaign")
      val computation =
        CampaignFinalizer()
          .compute(
            CampaignComputationRequest(
              campaignId = state.campaignId,
              performanceSessionId = state.performanceSessionId,
              profileFamily = state.profileFamily,
              attempts = attempts,
              baselineDistribution = state.baselineDistribution,
              candidateDistribution = state.candidateDistribution,
              qualificationRoot = qualificationRoot,
              qualification = campaignQualification,
              regressionPolicy = state.regressionPolicy,
              outputRoot = output,
              selectedAttemptId = attempts.lastOrNull { it.b != null }?.attemptId,
            ),
          )
      when (computation) {
        is CampaignComputationOutcome.Rejected ->
          finalizeFailure(artifactParent, runToken, computation.exit)
        is CampaignComputationOutcome.Computed ->
          EvidenceFinalizer.system()
            .finalizeCampaign(
              CampaignFinalizationRequest(output, artifactParent, runToken, computation.exit),
            )
      }
    } catch (_: Exception) {
      finalizeFailure(artifactParent, runToken, RunnerExit.INPUT_OR_PREFLIGHT_INVALID)
    } finally {
      cleanup(temp)
    }
  }

  private fun campaignQualification(
    root: Path,
    provisional: ProvisionalCaptureDocument,
  ): QualificationEvidence.ControlledMacCampaign =
    HostQualificationReader.read(
      root = root,
      policy = provisional.protocol.qualificationPolicySha256,
      substrate = provisional.runtime.substrate,
      campaign = true,
    ) as QualificationEvidence.ControlledMacCampaign

  private fun campaignState(root: Path): PrivateCampaignState {
    require(safeDirectory(root))
    val document = CanonicalJson.parseStrict(readCanonical(root.resolve(PrivateOperationWriter.CAMPAIGN_STATE))).asObject()
    require(document.text("schemaVersion") == "private-campaign-operation-v1")
    val policy = document.get("regressionPolicy")?.let { RegressionPolicy.parse(CanonicalJson.encode(it)) }
    return PrivateCampaignState(
      campaignId = document.text("campaignId"),
      performanceSessionId = document.text("performanceSessionId"),
      profileFamily = document.text("profileFamily"),
      baselineDistribution = Path.of(document.text("baselineDistribution")),
      candidateDistribution = Path.of(document.text("candidateDistribution")),
      regressionPolicy = policy,
      captures = document.arrayNode("captures").values().asSequence().map { it.asObject().captureState() }.toList(),
    ).also { require(it.captures.isNotEmpty()) }
  }

  private fun ObjectNode.captureState(): PrivateCaptureState =
    PrivateCaptureState(
      attemptId = text("attemptId"),
      documentFile = text("documentFile"),
      forks = int("forks"),
      operationRoot = Path.of(text("operationRoot")),
      receipt = objectNode("receipt").receipt(),
      role = CaptureRole.valueOf(text("role")),
    )

  private fun ObjectNode.receipt(): CampaignReceiptInput =
    CampaignReceiptInput(
      role = CaptureRole.valueOf(text("role")),
      distributionRoot = Path.of(text("distributionRoot")),
      manifestSha256 = sha("manifestSha256"),
      files = arrayNode("files").values().asSequence().map { value ->
        value.asObject().let { fact ->
          ReceiptFileFact(fact.text("relativePath"), fact.long("byteLength"), fact.sha("sha256"))
        }
      }.toList(),
      settleDuration = Duration.ofMillis(long("settleDurationMillis")),
      sequence = long("sequence"),
    )

  private fun PrivateCaptureState.input(sealedRoot: Path): CampaignCaptureInput =
    CampaignCaptureInput(role, sealedRoot, receipt)

  private fun finalizeFailure(
    artifactParent: Path,
    runToken: String,
    exit: RunnerExit,
  ): FinalizationOutcome =
    EvidenceFinalizer.system()
      .finalizeInvalid(
        InvalidFinalizationRequest(
          artifactParent,
          runToken,
          when (exit) {
            RunnerExit.INPUT_OR_PREFLIGHT_INVALID -> FinalizationFailure.INPUT_OR_PROTOCOL_INVALID
            RunnerExit.MEASUREMENT_INVALID -> FinalizationFailure.MEASUREMENT_INVALID
            RunnerExit.INCOMPATIBLE -> FinalizationFailure.COMPARISON_INCOMPATIBLE
            RunnerExit.CALIBRATION_FAILED -> FinalizationFailure.CALIBRATION_FAILED
            RunnerExit.POLICY_FAILED -> FinalizationFailure.POLICY_FAILED
            RunnerExit.POLICY_INCONCLUSIVE -> FinalizationFailure.POLICY_INCONCLUSIVE
            else -> FinalizationFailure.INTERNAL_ERROR
          },
        ),
      )

  private fun readCanonical(path: Path): ByteArray {
    require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
    val bytes = Files.readAllBytes(path)
    val document = CanonicalJson.parseStrict(bytes)
    require(CanonicalJson.encode(document).contentEquals(bytes))
    return bytes
  }

  private fun safeDirectory(path: Path): Boolean =
    path.isAbsolute && path == path.toAbsolutePath().normalize() &&
      Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

  private fun cleanup(root: Path) {
    if (!Files.exists(root, NOFOLLOW_LINKS)) return
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
  }

  private fun ObjectNode.text(name: String): String = get(name).asString()
  private fun ObjectNode.int(name: String): Int = get(name).asInt()
  private fun ObjectNode.long(name: String): Long = get(name).asLong()
  private fun ObjectNode.sha(name: String): Sha256 = Sha256.parse(text(name))
  private fun ObjectNode.objectNode(name: String): ObjectNode = get(name).asObject()
  private fun ObjectNode.arrayNode(name: String): ArrayNode = get(name).asArray()
}

private data class PrivateCampaignState(
  val campaignId: String,
  val performanceSessionId: String,
  val profileFamily: String,
  val baselineDistribution: Path,
  val candidateDistribution: Path,
  val regressionPolicy: RegressionPolicy?,
  val captures: List<PrivateCaptureState>,
)

private data class PrivateCaptureState(
  val attemptId: String,
  val documentFile: String,
  val forks: Int,
  val operationRoot: Path,
  val receipt: CampaignReceiptInput,
  val role: CaptureRole,
)
