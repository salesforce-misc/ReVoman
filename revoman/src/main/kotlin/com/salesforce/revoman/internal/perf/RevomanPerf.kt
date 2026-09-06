/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.perf

import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import java.util.concurrent.CancellationException
import jdk.jfr.FlightRecorder

/**
 * The only production instrumentation interface. Its disabled branch executes the supplied block
 * directly: it creates no event, identifier, metadata map, or listener notification.
 */
internal object RevomanPerf {
  inline fun <T> operation(kind: OperationKind, crossinline block: () -> T): T {
    if (!JfrRecorder.operationRecordingEnabled()) return block()
    return JfrRecorder.recordOperation(kind) { block() }
  }

  inline fun kick(kick: Kick, crossinline block: () -> Rundown): Rundown {
    if (!JfrRecorder.kickRecordingEnabled()) return block()
    return JfrRecorder.recordKick(kick) { block() }
  }

  inline fun step(
    position: Int,
    iteration: Int,
    environmentEntries: Int,
    step: Step,
    crossinline block: () -> StepReport,
  ): StepReport {
    if (!JfrRecorder.stepRecordingEnabled()) return block()
    return JfrRecorder.recordStep(position, iteration, environmentEntries, step) { block() }
  }
}

@PublishedApi
internal object JfrRecorder {
  @PublishedApi
  internal fun operationRecordingEnabled(): Boolean =
    FlightRecorder.isInitialized() && OperationRecorder.eventType.isEnabled

  @PublishedApi
  internal fun kickRecordingEnabled(): Boolean =
    FlightRecorder.isInitialized() && KickRecorder.eventType.isEnabled

  @PublishedApi
  internal fun stepRecordingEnabled(): Boolean =
    FlightRecorder.isInitialized() && StepRecorder.eventType.isEnabled

  @PublishedApi
  @Suppress("TooGenericExceptionCaught")
  internal fun <T> recordOperation(kind: OperationKind, block: () -> T): T {
    val event = OperationEvent()
    event.kind = kind.eventValue
    event.frequency = kind.frequencyValue
    event.begin()
    try {
      val result = block()
      event.completion = Completion.RETURNED
      return result
    } catch (failure: Throwable) {
      event.completion = completionFor(failure)
      event.failureClass = failure.javaClass
      throw failure
    } finally {
      event.commit()
    }
  }

  @PublishedApi
  @Suppress("TooGenericExceptionCaught")
  internal fun recordKick(kick: Kick, block: () -> Rundown): Rundown {
    val event = KickEvent()
    event.sourceCount = kick.templatePaths().size + kick.templateInputStreams().size
    event.initialEnvironmentEntries = kick.dynamicEnvironment().size
    event.begin()
    try {
      val rundown = block()
      event.providedStepCount = rundown.providedStepsToExecuteCount
      event.executedStepCount = rundown.stepReports.size
      event.finalEnvironmentEntries = rundown.mutableEnv.size
      event.stopReason = rundown.stopReason.name
      event.completion = Completion.RETURNED
      return rundown
    } catch (failure: Throwable) {
      event.completion = completionFor(failure)
      event.failureClass = failure.javaClass
      throw failure
    } finally {
      event.commit()
    }
  }

  @PublishedApi
  @Suppress("TooGenericExceptionCaught")
  internal fun recordStep(
    position: Int,
    iteration: Int,
    environmentEntries: Int,
    step: Step,
    block: () -> StepReport,
  ): StepReport {
    val event = StepEvent()
    event.position = position
    event.iteration = iteration
    event.environmentEntriesAtStart = environmentEntries
    event.preRequestScriptLineCount = scriptLineCount(step, "prerequest")
    event.postResponseScriptLineCount = scriptLineCount(step, "test")
    event.preStepHookCount = step.preStepHookCount
    event.postStepHookCount = step.postStepHookCount
    event.begin()
    try {
      val report = block()
      populate(event, report)
      event.completion = Completion.RETURNED
      return report
    } catch (failure: Throwable) {
      event.completion = completionFor(failure)
      event.failureClass = failure.javaClass
      throw failure
    } finally {
      event.commit()
    }
  }

  @PublishedApi
  internal fun completionFor(failure: Throwable): String =
    if (failure is CancellationException) Completion.CANCELLED else Completion.THREW

  @PublishedApi
  internal fun scriptLineCount(step: Step, listener: String): Int =
    step.rawPMStep.event?.firstOrNull { it.listen == listener }?.script?.exec?.size ?: 0

  @PublishedApi
  internal fun populate(event: StepEvent, report: StepReport) {
    event.environmentEntriesAtEnd = report.pmEnvSnapshot.size
    event.httpStatus =
      report.responseInfo?.fold({ -1 }, { response -> response.httpMsg.status.code }) ?: -1
    event.requestBytes =
      report.requestInfo?.fold({ -1L }, { request -> request.httpMsg.body.length ?: -1L }) ?: -1L
    event.responseBytes =
      report.responseInfo?.fold({ -1L }, { response -> response.httpMsg.body.length ?: -1L }) ?: -1L
    event.result =
      when {
        report.isLedgerSkipped -> StepResult.LEDGER_SKIPPED
        report.isRequestSkipped -> StepResult.REQUEST_SKIPPED
        report.isSuccessful -> StepResult.SUCCESS
        else -> StepResult.FAILURE
      }
    event.failurePhase = report.exeTypeForFailure?.let { OperationKind.from(it).eventValue } ?: ""
    event.failureClass = report.exeFailure?.failure?.javaClass
  }

  @PublishedApi
  internal object Completion {
    const val RETURNED = "RETURNED"
    const val THREW = "THREW"
    const val CANCELLED = "CANCELLED"
  }

  private object StepResult {
    const val SUCCESS = "SUCCESS"
    const val FAILURE = "FAILURE"
    const val LEDGER_SKIPPED = "LEDGER_SKIPPED"
    const val REQUEST_SKIPPED = "REQUEST_SKIPPED"
  }
}
