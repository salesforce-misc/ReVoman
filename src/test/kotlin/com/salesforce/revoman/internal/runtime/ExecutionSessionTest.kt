/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.compat.configuredRootJar
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.log.Banner
import com.salesforce.revoman.internal.log.RunLogContext
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.log.LogLevel
import com.salesforce.revoman.output.log.RunLogSink
import com.salesforce.revoman.output.log.StepEvent
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExecutionSessionTest {
  @BeforeEach
  fun resetBanner() {
    Banner.resetForTest()
    Banner.emitForTest = {}
    Banner.registerShutdownHookForTest = {}
  }

  @AfterEach
  fun cleanup() {
    RunLogContext.remove()
    Banner.resetForTest()
  }

  @Test
  fun `session preserves the exact observable kick envelope`() {
    val events = mutableListOf<String>()
    val previousSink = RecordingSink()
    val kickSink = RecordingSink()
    val kick = Kick.configure().runLogSink(kickSink).off()
    RunLogContext.install(previousSink)
    mockkObject(Banner)
    mockkObject(RunLogContext)
    try {
      every { Banner.onRunStart() } answers
        {
          events += "banner-start"
          callOriginal()
        }
      every { RunLogContext.install(any()) } answers
        {
          events += "install"
          callOriginal()
        }
      every { Banner.recordSteps(any()) } answers
        {
          events += "record"
          callOriginal()
        }
      every { RunLogContext.restore(any()) } answers
        {
          events += "restore"
          callOriginal()
        }
      val session =
        executionSession(
          emptyMap(),
          KickExecutionFactory { configuredKick, effectiveEnvironment ->
            events += "create"
            fakeExecution(
              configuredKick,
              effectiveEnvironment,
              events = events,
              execute = { oneStepRundown(mutableMapOf("result" to "ok")) },
            )
          },
        )

      val result =
        session.executeKick(kick, carryForward = false) { _, _ ->
          events += "callback"
          assertThat(RunLogContext.current()).isSameInstanceAs(previousSink)
        }

      assertThat(result.stepReports).hasSize(1)
      assertThat(events)
        .containsExactly(
          "banner-start",
          "install",
          "create",
          "execute",
          "close",
          "record",
          "restore",
          "callback",
        )
        .inOrder()
      assertThat(RunLogContext.current()).isSameInstanceAs(previousSink)
      assertThat(Banner.runCountForTest()).isEqualTo(1L)
      assertThat(Banner.stepCountForTest()).isEqualTo(1L)
      assertThat(kickSink.closed).isFalse()
      session.close()
    } finally {
      unmockkObject(RunLogContext)
      unmockkObject(Banner)
    }
  }

  @Test
  fun `built session records completed steps before restoring borrowed sink`() {
    val classpath = configuredRootJar().toAbsolutePath().toString()
    val process =
      ProcessBuilder(
          "javap",
          "-c",
          "-p",
          "-classpath",
          classpath,
          "com.salesforce.revoman.internal.runtime.ExecutionSessionKt\$executionSession\$1",
        )
        .redirectErrorStream(true)
        .start()
    val disassembly = process.inputStream.bufferedReader().use { it.readText() }
    assertThat(process.waitFor()).isEqualTo(0)
    val recordSteps = disassembly.indexOf("Banner.recordSteps")
    val restoreSink = disassembly.indexOf("RunLogContext.restore")

    assertThat(recordSteps).isAtLeast(0)
    assertThat(restoreSink).isGreaterThan(recordSteps)
  }

  @Test
  fun `session creates executes and closes exactly one child inside borrowed sink`() {
    val events = mutableListOf<String>()
    val sink = RecordingSink()
    val kick = Kick.configure().runLogSink(sink).off()
    val factory = KickExecutionFactory { configuredKick, effectiveEnvironment ->
      events += "create"
      fakeExecution(configuredKick, effectiveEnvironment, events = events)
    }
    val session = executionSession(emptyMap(), factory)

    val result = session.executeKick(kick, carryForward = false)

    assertThat(result.mutableEnv).containsEntry("effective", emptyMap<String, Any?>())
    assertThat(events).containsExactly("create", "execute", "close").inOrder()
    assertThat(sink.closed).isFalse()
    assertThat(RunLogContext.current()).isNull()
    assertThat(Banner.runCountForTest()).isEqualTo(1L)
    assertThat(Banner.stepCountForTest()).isEqualTo(0L)
    session.close()
  }

  @Test
  fun `carried environment wins and fresh post-callback snapshot is detached`() {
    val observed = mutableListOf<Map<String, Any?>>()
    val rundowns = mutableListOf<Rundown>()
    val session =
      executionSession(
        initialEnvironment = mapOf("shared" to "carried"),
        kickExecutions =
          KickExecutionFactory { kick, effective ->
            observed += effective
            fakeExecution(kick, effective) {
              rundown(effective.toMutableMap()).also(rundowns::add)
            }
          },
      )
    val configured = Kick.configure().dynamicEnvironment("shared", "configured").off()

    val first =
      session.executeKick(configured, carryForward = true) { rundown, snapshot ->
        assertThat(snapshot).containsExactly(rundown)
        rundown.immutableEnv
        rundown.mutableEnv["shared"] = "callback"
        rundown.mutableEnv["late"] = 7
      }
    first.mutableEnv["late"] = 99
    session.executeKick(Kick.configure().off(), carryForward = false)

    assertThat(observed[0]).containsEntry("shared", "carried")
    assertThat(observed[1]).containsEntry("shared", "callback")
    assertThat(observed[1]).containsEntry("late", 7)
    session.close()
  }

  @Test
  fun `factory failure transfers no child ownership`() {
    val constructionFailure = IllegalStateException("construction")
    var factoryRollbacks = 0
    val session =
      executionSession(
        emptyMap(),
        KickExecutionFactory { _, _ ->
          val partialScope = resourceScope()
          partialScope.own(
            object : InternalCloseable {
              override fun close() {
                factoryRollbacks++
              }
            }
          )
          try {
            throw constructionFailure
          } catch (failure: Throwable) {
            partialScope.closeAfter(failure)
            throw failure
          }
        },
      )

    val thrown =
      assertThrows<IllegalStateException> {
        session.executeKick(Kick.configure().off(), carryForward = false)
      }
    session.close()

    assertThat(thrown).isSameInstanceAs(constructionFailure)
    assertThat(factoryRollbacks).isEqualTo(1)
  }

  @Test
  fun `body failure preserves propagated child close throwable without flattening or retry`() {
    val bodyFailure = IllegalStateException("body")
    val closeFailure = IllegalArgumentException("close")
    val nestedFirst = IllegalStateException("nested-first")
    val nestedSecond = IllegalStateException("nested-second")
    closeFailure.addSuppressed(nestedFirst)
    closeFailure.addSuppressed(nestedSecond)
    var closeCount = 0
    val session =
      executionSession(
        emptyMap(),
        KickExecutionFactory { kick, effective ->
          fakeExecution(
            kick,
            effective,
            execute = { throw bodyFailure },
            close = {
              closeCount++
              throw closeFailure
            },
          )
        },
      )

    val thrown =
      assertThrows<IllegalStateException> {
        session.executeKick(Kick.configure().off(), carryForward = false)
      }
    session.close()

    assertThat(thrown).isSameInstanceAs(bodyFailure)
    assertThat(thrown.suppressed).hasLength(1)
    assertThat(thrown.suppressed.single()).isSameInstanceAs(closeFailure)
    assertThat(closeFailure.suppressed).hasLength(2)
    assertThat(closeFailure.suppressed[0]).isSameInstanceAs(nestedFirst)
    assertThat(closeFailure.suppressed[1]).isSameInstanceAs(nestedSecond)
    assertThat(nestedFirst.suppressed).isEmpty()
    assertThat(nestedSecond.suppressed).isEmpty()
    assertThat(closeCount).isEqualTo(1)
    assertThat(Banner.stepCountForTest()).isEqualTo(0L)
  }

  @Test
  fun `successful body close failure prevents finalization and is not retried`() {
    val closeFailure = IllegalStateException("close")
    var closeCount = 0
    var callbackCount = 0
    val session =
      executionSession(
        emptyMap(),
        KickExecutionFactory { kick, effective ->
          fakeExecution(
            kick,
            effective,
            execute = { oneStepRundown(mutableMapOf("result" to "complete")) },
            close = {
              closeCount++
              throw closeFailure
            },
          )
        },
      )

    val thrown =
      assertThrows<IllegalStateException> {
        session.executeKick(Kick.configure().off(), carryForward = true) { _, _ -> callbackCount++ }
      }
    session.close()

    assertThat(thrown).isSameInstanceAs(closeFailure)
    assertThat(closeCount).isEqualTo(1)
    assertThat(callbackCount).isEqualTo(0)
    assertThat(Banner.runCountForTest()).isEqualTo(1L)
    assertThat(Banner.stepCountForTest()).isEqualTo(0L)
  }

  @Test
  fun `callback failure observes closed child and session close does not retry it`() {
    val callbackFailure = IllegalStateException("callback")
    var closeCount = 0
    val session =
      executionSession(
        emptyMap(),
        KickExecutionFactory { kick, effective ->
          fakeExecution(kick, effective, close = { closeCount++ })
        },
      )

    val thrown =
      assertThrows<IllegalStateException> {
        session.executeKick(Kick.configure().off(), carryForward = true) { _, _ ->
          assertThat(closeCount).isEqualTo(1)
          throw callbackFailure
        }
      }
    session.close()

    assertThat(thrown).isSameInstanceAs(callbackFailure)
    assertThat(closeCount).isEqualTo(1)
  }

  @Test
  fun `fresh snapshot failure occurs after child close and is not retried`() {
    val snapshotFailure = IllegalStateException("snapshot")
    var closeCount = 0
    val failingEnvironment =
      object : LinkedHashMap<String, Any?>() {
        override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
          get() = throw snapshotFailure
      }
    failingEnvironment["present"] = true
    val failedRundown = rundown(failingEnvironment)
    val session =
      executionSession(
        emptyMap(),
        KickExecutionFactory { kick, effective ->
          fakeExecution(
            kick,
            effective,
            execute = { failedRundown },
            close = { closeCount++ },
          )
        },
      )

    val thrown =
      assertThrows<IllegalStateException> {
        session.executeKick(Kick.configure().off(), carryForward = true)
      }
    session.close()

    assertThat(thrown).isSameInstanceAs(snapshotFailure)
    assertThat(closeCount).isEqualTo(1)
  }

  @Test
  fun `close is idempotent rejects later execution and leaves transferred peer scopes valid`() {
    val environment = mutableMapOf<String, Any?>("environment" to 1)
    val collection = mutableMapOf<String, Any?>("collection" to 2)
    val globals = mutableMapOf<String, Any?>("global" to 3)
    val expected =
      Rundown(
        mutableEnv = PostmanEnvironment(environment),
        haltOnFailureOfTypeExcept = emptyMap(),
        providedStepsToExecuteCount = 0,
        collectionVariables = PostmanEnvironment(collection),
        globals = PostmanEnvironment(globals),
      )
    val session =
      executionSession(
        emptyMap(),
        KickExecutionFactory { kick, effective ->
          fakeExecution(kick, effective, execute = { expected })
        },
      )

    val returned = session.executeKick(Kick.configure().off(), carryForward = false)
    session.close()
    session.close()

    assertThat(returned.mutableEnv).containsEntry("environment", 1)
    assertThat(returned.collectionVariables).containsEntry("collection", 2)
    assertThat(returned.globals).containsEntry("global", 3)
    assertThrows<IllegalStateException> {
      session.executeKick(Kick.configure().off(), carryForward = false)
    }
  }

  private fun fakeExecution(
    kick: Kick,
    effective: Map<String, Any?>,
    events: MutableList<String> = mutableListOf(),
    execute: () -> Rundown = { rundown(mutableMapOf("effective" to effective)) },
    close: () -> Unit = {},
  ): KickExecution =
    object : KickExecution {
      override val configuredKick: Kick = kick
      override val effectiveDynamicEnvironment: Map<String, Any?> = effective
      override val scripts: ScriptExecutor
        get() = error("scripts are not used")

      override val sandboxInitialized: Boolean = false

      override fun execute(): Rundown {
        assertThat(RunLogContext.current()).isSameInstanceAs(kick.runLogSink())
        events += "execute"
        return execute.invoke()
      }

      override fun close() {
        assertThat(RunLogContext.current()).isSameInstanceAs(kick.runLogSink())
        events += "close"
        close.invoke()
      }
    }

  private fun rundown(
    environment: MutableMap<String, Any?>,
    stepReports: List<StepReport> = emptyList(),
  ): Rundown =
    Rundown(
      stepReports = stepReports,
      mutableEnv = PostmanEnvironment(environment),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = stepReports.size,
    )

  private fun oneStepRundown(environment: MutableMap<String, Any?>): Rundown {
    val snapshot = PostmanEnvironment(environment.toMutableMap())
    val report =
      StepReport(
        step = Step(index = "1", rawPMStep = Item(name = "one")),
        pmEnvSnapshot = snapshot,
      )
    return rundown(environment, listOf(report))
  }

  private class RecordingSink : RunLogSink {
    var closed = false

    override fun line(level: LogLevel, message: String) = Unit

    override fun event(event: StepEvent) = Unit

    override fun close() {
      closed = true
    }
  }
}
