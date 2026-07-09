/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.output.ExeType
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.TxnInfo.Companion.uriPathContains
import com.salesforce.revoman.output.report.TxnInfo.Companion.uriPathEndsWith
import com.salesforce.revoman.output.report.failure.ExeFailure
import com.salesforce.revoman.output.report.failure.HookFailure.PostStepHookFailure
import com.salesforce.revoman.output.report.failure.HookFailure.PreStepHookFailure
import com.salesforce.revoman.output.report.failure.HttpStatusUnsuccessful
import com.salesforce.revoman.output.report.failure.PmTestFailure
import com.salesforce.revoman.output.report.failure.PollingFailure
import com.salesforce.revoman.output.report.failure.RequestFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure
import com.salesforce.revoman.output.report.failure.buildPmTestFailures
import io.vavr.control.Either
import io.vavr.control.Either.left
import io.vavr.control.Either.right
import java.time.Duration
import org.http4k.core.Request
import org.http4k.core.Response

data class StepReport
internal constructor(
  @JvmField val step: Step,
  @JvmField val requestInfo: Either<out RequestFailure, TxnInfo<Request>>? = null,
  @JvmField val preStepHookFailure: PreStepHookFailure? = null,
  @JvmField val responseInfo: Either<out ResponseFailure, TxnInfo<Response>>? = null,
  @JvmField val postStepHookFailure: PostStepHookFailure? = null,
  @JvmField val pollingFailure: PollingFailure? = null,
  @JvmField val pollingReport: PollingReport? = null,
  @JvmField val exeTimings: Map<ExeType, Duration> = emptyMap(),
  @JvmField val pmEnvSnapshot: PostmanEnvironment<Any?>,
  @JvmField val envVars: StepEnvVars = StepEnvVars(),
  /** `pm.test(...)` results recorded across this step's scripts (pre-request and post-response). */
  @JvmField val pmTestAssertions: List<PmTestAssertion> = emptyList(),
  /**
   * Next request set via `pm.execution.setNextRequest(...)` in this step's scripts, if any. Now
   * HONORED by the sequencer: a name causes a jump, a null causes a run stop.
   */
  @JvmField val nextRequest: String? = null,
  /** True iff `setNextRequest` was called at all (distinguishes `setNextRequest(null)` STOP). */
  @JvmField val nextRequestSet: Boolean = false,
  /**
   * Iteration index of this execution of the step (0 for the common single-run case; >0 in a loop).
   */
  @JvmField val iteration: Int = 0,
  /**
   * Internal marker: this report is a pre-request `skipRequest()` skip (set by [requestSkipped]).
   */
  @JvmField val requestSkippedFlag: Boolean = false,
) {
  internal constructor(
    step: Step,
    requestInfo: arrow.core.Either<RequestFailure, TxnInfo<Request>>? = null,
    preStepHookFailure: PreStepHookFailure? = null,
    responseInfo: arrow.core.Either<ResponseFailure, TxnInfo<Response>>? = null,
    postStepHookFailure: PostStepHookFailure? = null,
    pollingFailure: PollingFailure? = null,
    pollingReport: PollingReport? = null,
    exeTimings: Map<ExeType, Duration> = emptyMap(),
    pmEnvSnapshot: PostmanEnvironment<Any?>,
  ) : this(
    step,
    requestInfo?.toVavr(),
    preStepHookFailure,
    responseInfo?.toVavr(),
    postStepHookFailure,
    pollingFailure,
    pollingReport,
    exeTimings,
    pmEnvSnapshot,
  )

  /**
   * pm.test failures of this step, grouped by phase (0–2 entries, pre-request first). ALWAYS
   * populated when any assertion failed, INDEPENDENT of [failure]'s precedence — so a co-occurring
   * HTTP/transport failure (which wins [failure]) does not hide the assertion failure.
   */
  @JvmField val pmTestFailure: List<PmTestFailure> = buildPmTestFailures(pmTestAssertions)

  @JvmField
  val failure: Either<ExeFailure, HttpStatusUnsuccessful>? =
    failure(
      requestInfo,
      preStepHookFailure,
      responseInfo,
      postStepHookFailure,
      pollingFailure,
      pmTestFailure,
    )

  @JvmField val exeTypeForFailure: ExeType? = failure?.fold({ it.exeType }, { it.exeType })

  @JvmField val exeFailure: ExeFailure? = failure?.fold({ it }, { null })

  @JvmField val isSuccessful: Boolean = failure == null

  @JvmField
  val isHttpStatusSuccessful: Boolean =
    failure?.fold(
      { it is PostStepHookFailure || it is PollingFailure || it is PmTestFailure },
      { false },
    ) ?: true

  /**
   * True when this step's HTTP dispatch was SKIPPED on a warm run by the ledger (its produced keys
   * were reused, not re-executed) — see [Companion.ledgerSkipped]. Such a report carries NO
   * [requestInfo]/[responseInfo] (nothing was sent), so reading its response yields null. A test
   * that asserts on this step's response body must ensure the step opts out of the ledger
   * ([Step.optsOutOfLedger] / `ledgerOptOutSteps`); the [assertNotLedgerSkipped] guard fails loud
   * if it didn't. Uniquely identifiable as a successful report that nonetheless never sent a
   * request.
   */
  @JvmField
  val isLedgerSkipped: Boolean =
    isSuccessful && requestInfo == null && responseInfo == null && !requestSkippedFlag

  /**
   * True when this step's HTTP dispatch was SKIPPED by a pre-request `pm.execution.skipRequest()`.
   * Like [isLedgerSkipped] it carries no [requestInfo]/[responseInfo], but it is a script-driven
   * skip (not a ledger reuse) and produces no env vars. Distinguished from [isLedgerSkipped] via an
   * explicit marker so the two never conflate.
   */
  @JvmField val isRequestSkipped: Boolean = requestSkippedFlag

  companion object {
    /**
     * A RECORDED report for a step whose HTTP dispatch was skipped on a warm run because the ledger
     * already carried its [produced] keys (reused, not re-executed). [env] is snapshotted so the
     * report reflects the env AFTER the ledgered values were injected. [consumed] is carried
     * through from the reused ledger entry so the re-emitted `learnedLedger` keeps the provenance
     * graph — otherwise a warm-run merge (last-write-wins) would erase consumed on every loop.
     */
    @JvmStatic
    @JvmOverloads
    fun ledgerSkipped(
      step: Step,
      produced: Set<String>,
      env: PostmanEnvironment<Any?>,
      consumed: Set<String> = emptySet(),
    ): StepReport =
      StepReport(
        step = step,
        pmEnvSnapshot = env.copy(mutableEnv = env.mutableEnv.toMutableMap()),
        envVars = StepEnvVars(produced = produced, consumed = consumed),
      )

    /**
     * A RECORDED report for a step whose HTTP dispatch was skipped by a pre-request
     * `pm.execution.skipRequest()`. Successful (a skip is not a failure), carries no
     * request/response, produces no env vars. [iteration] tags the loop iteration (0 if not
     * looped).
     */
    @JvmStatic
    @JvmOverloads
    fun requestSkipped(
      step: Step,
      env: PostmanEnvironment<Any?>,
      iteration: Int = 0,
    ): StepReport =
      StepReport(
        step = step,
        pmEnvSnapshot = env.copy(mutableEnv = env.mutableEnv.toMutableMap()),
        iteration = iteration,
        requestSkippedFlag = true,
      )

    private fun failure(
      requestInfo: Either<out ExeFailure, TxnInfo<Request>>? = null,
      preStepHookFailure: PreStepHookFailure? = null,
      responseInfo: Either<out ExeFailure, TxnInfo<Response>>? = null,
      postStepHookFailure: PostStepHookFailure? = null,
      pollingFailure: PollingFailure? = null,
      pmTestFailure: List<PmTestFailure> = emptyList(),
    ): Either<ExeFailure, HttpStatusUnsuccessful>? =
      when {
        requestInfo != null ->
          when (requestInfo) {
            is Either.Left -> left(requestInfo.left)
            else ->
              when {
                preStepHookFailure != null -> left(preStepHookFailure)
                responseInfo != null ->
                  when (responseInfo) {
                    is Either.Left -> left(responseInfo.left)
                    is Either.Right ->
                      when {
                        !responseInfo.get().httpMsg.status.successful ->
                          right(HttpStatusUnsuccessful(requestInfo.get(), responseInfo.get()))
                        else ->
                          when {
                            postStepHookFailure != null -> left(postStepHookFailure)
                            pollingFailure != null -> left(pollingFailure)
                            pmTestFailure.isNotEmpty() -> left(pmTestFailure.first())
                            else -> null
                          }
                      }
                    else -> null
                  }
                else -> null
              }
          }
        else -> null
      }

    /**
     * Guardrail for tests that read a step's response: throws if [stepReport] was
     * [isLedgerSkipped], because its response body is null/stale (the request was never sent on
     * this warm run). The fix the message points at: mark the step to opt out of the ledger —
     * either the per-step `x-revoman-ledger: off` header or a Kick-level `ledgerOptOutSteps(...)`
     * pick — so it always dispatches fresh. Call this from a test's response-reading accessor (e.g.
     * a `rawResponse(stepReport)` helper) so an omission fails loudly at run time instead of
     * silently asserting on cached data. No-op for a normally executed step.
     */
    @JvmStatic
    fun assertNotLedgerSkipped(stepReport: StepReport) {
      check(!stepReport.isLedgerSkipped) {
        "Step '${stepReport.step}' was LEDGER-SKIPPED, so its response is null — a test must not " +
          "assert on a ledger-skipped step's response. Mark it to opt out of the ledger: add the " +
          "`x-revoman-ledger: off` request header, or a Kick-level " +
          "`ledgerOptOutSteps(ExeStepPick.stepEndingWithURIPathOfAny(\"<uri>\"))` pick, so it " +
          "always dispatches fresh."
      }
    }

    fun <L, R> arrow.core.Either<L, R>.toVavr(): Either<L, R> = fold({ left(it) }, { right(it) })

    @JvmStatic
    fun Either<out RequestFailure, TxnInfo<Request>>?.uriPathContains(path: String): Boolean =
      this?.fold({ false }, { it.uriPathContains(path) }) ?: false

    @JvmStatic
    fun Either<out RequestFailure, TxnInfo<Request>>?.uriPathEndsWith(path: String): Boolean =
      this?.fold({ false }, { it.uriPathEndsWith(path) }) ?: false

    @JvmStatic
    fun Either<out RequestFailure, TxnInfo<Request>>?.containsHeader(key: String): Boolean =
      this?.fold({ false }, { it.containsHeader(key) }) ?: false

    @JvmStatic
    fun Either<out RequestFailure, TxnInfo<Request>>?.containsHeader(
      key: String,
      value: String,
    ): Boolean = this?.fold({ false }, { it.containsHeader(key, value) }) ?: false
  }

  override fun toString(): String =
    "$step" +
      when {
        exeFailure != null -> " =>> ❌$exeFailure\n${exeFailure.failure.stackTraceToString()}"
        !isHttpStatusSuccessful ->
          " =>> ⚠️️Unsuccessful HTTP Status: ${responseInfo?.get()?.httpMsg?.status}\n${requestInfo?.get()}\n${responseInfo?.get()}"
        else -> "✅${requestInfo?.get()}\n${responseInfo?.get()}"
      }
}
