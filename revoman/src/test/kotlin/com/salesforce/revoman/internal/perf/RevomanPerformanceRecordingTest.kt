/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.perf

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.ledger.LedgerEntry
import com.salesforce.revoman.output.ledger.LedgerSnapshot
import java.nio.file.Path
import java.util.concurrent.CancellationException
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RevomanPerformanceRecordingTest {

  @TempDir lateinit var tempDir: Path

  @Test
  fun `revUp emits nested semantic events only when recording enables them`() {
    val events = recordPerformanceEvents {
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v3/single-ok")
          .dynamicEnvironment("baseUrl", "http://instrumentation.invalid")
          .httpClient { Response(OK).body("{}") }
          .off()
      )
    }

    val kick = events.single { it.eventType.name == KICK_EVENT }
    assertThat(kick.getInt("providedStepCount")).isEqualTo(1)
    assertThat(kick.getInt("executedStepCount")).isEqualTo(1)
    assertThat(kick.getString("stopReason")).isEqualTo("COMPLETED")

    val step = events.single { it.eventType.name == STEP_EVENT }
    assertThat(step.getInt("position")).isEqualTo(0)
    assertThat(step.getInt("iteration")).isEqualTo(0)
    assertThat(step.getString("result")).isEqualTo("SUCCESS")

    assertThat(operationKinds(events))
      .containsAtLeast(
        "COLLECTION_DECODE_V3_PATH",
        "COLLECTION_FLATTEN",
        "DYNAMIC_VARIABLE_RUNTIME_PREPARE",
        "JSON_RUNTIME_PREPARE",
        "ENVIRONMENT_LOAD",
        "SDK_RUNTIME_PREPARE",
        "HTTP_CLIENT_PREPARE",
        "SEQUENCE_EXECUTE",
        "ENVIRONMENT_REBUILD",
        "REQUEST_BUILD",
        "HTTP_EXCHANGE",
        "SANDBOX_CLOSE",
        "RUN_FINALIZE",
      )
  }

  @Test
  fun `returned failure stays distinct from a thrown operation`() {
    val events = recordPerformanceEvents {
      ReVoman.revUp(oneStepKick { throw IllegalStateException("handler failed") })
    }

    val step = events.single { it.eventType.name == STEP_EVENT }
    assertThat(step.getString("result")).isEqualTo("FAILURE")
    assertThat(step.getString("failurePhase")).isEqualTo("HTTP_EXCHANGE")
    assertThat(step.getString("completion")).isEqualTo("RETURNED")
    assertThat(step.getClass("failureClass").name).isEqualTo(IllegalStateException::class.java.name)

    val exchange = events.single {
      it.eventType.name == OPERATION_EVENT && it.getString("kind") == "HTTP_EXCHANGE"
    }
    assertThat(exchange.getString("frequency")).isEqualTo("STEP")
    assertThat(exchange.getString("completion")).isEqualTo("RETURNED")
    assertThat(exchange.getValue<Class<*>?>("failureClass")).isNull()
  }

  @Test
  fun `operation records throws and cancellation without replacing the throwable`() {
    val thrown = IllegalArgumentException("same instance")
    val cancelled = CancellationException("cancelled")
    var observedThrow: Throwable? = null
    var observedCancellation: Throwable? = null

    val events = recordPerformanceEvents {
      try {
        RevomanPerf.operation(OperationKind.REQUEST_BUILD) { throw thrown }
      } catch (failure: Throwable) {
        observedThrow = failure
      }
      try {
        RevomanPerf.operation(OperationKind.SANDBOX_BOOT) { throw cancelled }
      } catch (failure: Throwable) {
        observedCancellation = failure
      }
    }

    assertThat(observedThrow).isSameInstanceAs(thrown)
    assertThat(observedCancellation).isSameInstanceAs(cancelled)
    assertThat(operation(events, "REQUEST_BUILD").getString("completion")).isEqualTo("THREW")
    assertThat(operation(events, "SANDBOX_BOOT").getString("completion")).isEqualTo("CANCELLED")
  }

  @Test
  fun `multi kick and step event counts expose execution frequency`() {
    val kicks = listOf(oneStepKick(), oneStepKick())

    val events = recordPerformanceEvents { ReVoman.revUp(kicks) }

    assertThat(events.count { it.eventType.name == KICK_EVENT }).isEqualTo(2)
    assertThat(events.count { it.eventType.name == STEP_EVENT }).isEqualTo(2)
    assertThat(operationKinds(events).count { it == "MULTI_KICK" }).isEqualTo(1)
    assertThat(operationKinds(events).count { it == "POST_EXECUTION_HOOK" }).isEqualTo(2)
    assertThat(operationKinds(events).count { it == "HTTP_EXCHANGE" }).isEqualTo(2)
  }

  @Test
  fun `request skip and sandbox lifecycle stay visible as domain outcomes`() {
    val events = recordPerformanceEvents {
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v3/cf-skip")
          .dynamicEnvironment("baseUrl", "http://instrumentation.invalid")
          .httpClient { Response(OK).body("{}") }
          .off()
      )
    }

    assertThat(stepResults(events)).contains("REQUEST_SKIPPED")
    assertThat(operationKinds(events)).contains("SANDBOX_BOOT")
    assertThat(operationKinds(events)).contains("SANDBOX_CLOSE")
  }

  @Test
  fun `ledger skip stays visible without an HTTP exchange`() {
    val coldKick =
      Kick.configure()
        .templatePath("pm-templates/v3/ledger-skip")
        .dynamicEnvironment("baseUrl", "http://instrumentation.invalid")
        .httpClient { Response(OK).body("{}") }
        .off()
    val coldStep = ReVoman.revUp(coldKick).stepReports.single().step
    val producedKey = "recordedLedgerValue"
    val ledger =
      LedgerSnapshot(
        orgId = null,
        steps = mapOf(coldStep.path to LedgerEntry(setOf(producedKey), coldStep.sourceHash)),
        values = mapOf(producedKey to "reused"),
      )

    val events = recordPerformanceEvents { ReVoman.revUp(coldKick.overrideLedger(ledger)) }

    assertThat(stepResults(events)).containsExactly("LEDGER_SKIPPED")
    assertThat(operationKinds(events)).doesNotContain("HTTP_EXCHANGE")
  }

  @Test
  fun `events remain off under an otherwise active recording`() {
    val recordingFile = tempDir.resolve("disabled-performance.jfr")
    Recording().use { recording ->
      recording.enable("jdk.ThreadSleep")
      recording.start()
      ReVoman.revUp(oneStepKick())
      recording.stop()
      recording.dump(recordingFile)
    }

    assertThat(
        RecordingFile.readAllEvents(recordingFile).filter {
          it.eventType.name in PERFORMANCE_EVENTS
        }
      )
      .isEmpty()
  }

  private fun recordPerformanceEvents(block: () -> Unit): List<RecordedEvent> {
    val recordingFile = tempDir.resolve("revoman-performance.jfr")
    Recording().use { recording ->
      recording.enable(OPERATION_EVENT)
      recording.enable(KICK_EVENT)
      recording.enable(STEP_EVENT)
      recording.start()
      block()
      recording.stop()
      recording.dump(recordingFile)
    }
    return RecordingFile.readAllEvents(recordingFile).filter {
      it.eventType.name in PERFORMANCE_EVENTS
    }
  }

  private fun operationKinds(events: List<RecordedEvent>): List<String> =
    events.filter { it.eventType.name == OPERATION_EVENT }.map { it.getString("kind") }

  private fun stepResults(events: List<RecordedEvent>): List<String> =
    events.filter { it.eventType.name == STEP_EVENT }.map { it.getString("result") }

  private fun operation(events: List<RecordedEvent>, kind: String): RecordedEvent = events.single {
    it.eventType.name == OPERATION_EVENT && it.getString("kind") == kind
  }

  private fun oneStepKick(
    handler: org.http4k.core.HttpHandler = { Response(OK).body("{}") }
  ): Kick =
    Kick.configure()
      .templatePath("pm-templates/v3/single-ok")
      .dynamicEnvironment("baseUrl", "http://instrumentation.invalid")
      .httpClient(handler)
      .off()

  private companion object {
    const val OPERATION_EVENT = "com.salesforce.revoman.performance.Operation"
    const val KICK_EVENT = "com.salesforce.revoman.performance.Kick"
    const val STEP_EVENT = "com.salesforce.revoman.performance.Step"
    val PERFORMANCE_EVENTS = setOf(OPERATION_EVENT, KICK_EVENT, STEP_EVENT)
  }
}
