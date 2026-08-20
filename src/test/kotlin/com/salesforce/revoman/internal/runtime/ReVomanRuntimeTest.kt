/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.PostExeHook
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionResult
import com.salesforce.revoman.internal.postman.sandbox.ScriptTarget
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.log.LogLevel
import com.salesforce.revoman.output.log.RunLogSink
import com.salesforce.revoman.output.log.StepEvent
import com.salesforce.revoman.output.postman.PostmanEnvironment
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReVomanRuntimeTest {
  @Test
  fun `empty list runtime still opens and closes one session`() {
    var opens = 0
    var closes = 0
    val runtime =
      reVomanRuntime(
        ExecutionSessionFactory { initial ->
          opens++
          assertThat(initial).containsExactly("seed", 1)
          object : ExecutionSession {
            override fun executeKick(
              configuredKick: Kick,
              carryForward: Boolean,
              beforeCarry: ((Rundown, List<Rundown>) -> Unit)?,
            ): Rundown = error("empty list must not create a child")

            override fun close() {
              closes++
            }
          }
        }
      )

    val result = runtime.execute(emptyList(), PostExeHook { _, _ -> }, mapOf("seed" to 1))

    assertThat(result).isEmpty()
    assertThat(opens).isEqualTo(1)
    assertThat(closes).isEqualTo(1)
  }

  @Test
  fun `single kick runtime opens and closes exactly one session`() {
    var opens = 0
    var closes = 0
    var creates = 0
    val expected = rundown(mutableMapOf("result" to "ok"))
    val childFactory = KickExecutionFactory { _, _ ->
      creates++
      fakeExecution(expected)
    }
    val runtime =
      reVomanRuntime(
        ExecutionSessionFactory { initial ->
          opens++
          val delegate = executionSession(initial, childFactory)
          object : ExecutionSession by delegate {
            override fun close() {
              closes++
              delegate.close()
            }
          }
        }
      )

    val actual = runtime.execute(Kick.configure().off())

    assertThat(actual).isSameInstanceAs(expected)
    assertThat(opens).isEqualTo(1)
    assertThat(creates).isEqualTo(1)
    assertThat(closes).isEqualTo(1)
  }

  @Test
  fun `runtime closes its session when child body fails`() {
    val failure = IllegalStateException("body")
    var closes = 0
    val runtime =
      reVomanRuntime(
        ExecutionSessionFactory { initial ->
          val delegate =
            executionSession(
              initial,
              KickExecutionFactory { _, _ ->
                fakeExecution(rundown(mutableMapOf()), failure)
              },
            )
          object : ExecutionSession by delegate {
            override fun close() {
              closes++
              delegate.close()
            }
          }
        }
      )

    val thrown =
      assertThrows<IllegalStateException> {
        runtime.execute(Kick.configure().off())
      }

    assertThat(thrown).isSameInstanceAs(failure)
    assertThat(closes).isEqualTo(1)
  }

  @Test
  fun `real no-script path never creates sandbox runtime`() {
    val sandboxes = CountingSandboxFactory()
    val lifecycle = recordingRealRuntime(sandboxes)

    val rundown = lifecycle.runtime.execute(Kick.configure().off())

    assertThat(rundown.stepReports).isEmpty()
    assertCompleteSingleKickLifecycle(lifecycle.counts)
    assertThat(sandboxes.createCount).isEqualTo(0)
    assertThat(sandboxes.closeCount).isEqualTo(0)
  }

  @Suppress("DEPRECATION")
  @Test
  fun `deprecated node modules path reports that it is ignored`() {
    val sink = RecordingSink()
    val lifecycle = recordingRealRuntime(CountingSandboxFactory())
    val kick = Kick.configure().nodeModulesPath("custom-modules").runLogSink(sink).off()

    lifecycle.runtime.execute(kick)

    assertThat(sink.lines)
      .contains(
        LogLevel.WARN to
          "nodeModulesPath(...) is ignored; sandbox scripts support only bundled modules"
      )
  }

  @Test
  fun `real script path creates and closes exactly one sandbox runtime`() {
    val sandboxes = CountingSandboxFactory()
    val lifecycle = recordingRealRuntime(sandboxes)

    lifecycle.runtime.execute(scriptKick())

    assertCompleteSingleKickLifecycle(lifecycle.counts)
    assertThat(sandboxes.createCount).isEqualTo(1)
    assertThat(sandboxes.closeCount).isEqualTo(1)
    assertThat(sandboxes.executeCount).isEqualTo(1)
  }

  @Test
  fun `single kick real lifecycle never materializes carry snapshot`() {
    val snapshotFailure = IllegalStateException("carry snapshot traversed")
    val sandboxes = CountingSandboxFactory()
    val lifecycle =
      recordingRealRuntime(sandboxes) { rundown ->
        val safeValues = rundown.mutableEnv.toMap()
        rundown.copy(
          mutableEnv = PostmanEnvironment(TraversalFailingMap(safeValues, snapshotFailure))
        )
      }

    val returned = lifecycle.runtime.execute(scriptKick())

    assertThat(returned.mutableEnv["baseUrl"]).isEqualTo("http://127.0.0.1:1")
    val thrown = assertThrows<IllegalStateException> { returned.mutableEnv.toMap() }
    assertThat(thrown).isSameInstanceAs(snapshotFailure)
    assertCompleteSingleKickLifecycle(lifecycle.counts)
    assertThat(sandboxes.createCount).isEqualTo(1)
    assertThat(sandboxes.executeCount).isEqualTo(1)
    assertThat(sandboxes.closeCount).isEqualTo(1)
  }

  private fun fakeExecution(
    rundown: Rundown,
    failure: Throwable? = null,
  ): KickExecution =
    object : KickExecution {
      override fun execute(): Rundown {
        failure?.let { throw it }
        return rundown
      }

      override fun close() = Unit
    }

  private fun rundown(environment: MutableMap<String, Any?>): Rundown =
    Rundown(
      mutableEnv = PostmanEnvironment(environment),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 0,
    )

  private fun scriptKick(): Kick =
    Kick.configure()
      .templatePath("pm-templates/v3/cf-skip")
      .dynamicEnvironment("baseUrl", "http://127.0.0.1:1")
      .off()

  private fun recordingRealRuntime(
    sandboxFactory: SandboxFactory,
    transformResult: (Rundown) -> Rundown = { it },
  ): RecordingRealRuntime {
    val counts = LifecycleCounts()
    val productionChildren = kickExecutionFactory(sandboxFactory)
    val recordingChildren = KickExecutionFactory { kick, environment ->
      counts.childCreates++
      val delegate = productionChildren.create(kick, environment)
      object : KickExecution by delegate {
        override fun execute(): Rundown {
          counts.childExecutes++
          return transformResult(delegate.execute())
        }

        override fun close() {
          counts.childCloses++
          delegate.close()
        }
      }
    }
    val productionSessions = executionSessionFactory(recordingChildren)
    val recordingSessions = ExecutionSessionFactory { initialEnvironment ->
      counts.sessionOpens++
      val delegate = productionSessions.open(initialEnvironment)
      object : ExecutionSession by delegate {
        override fun close() {
          counts.sessionCloses++
          delegate.close()
        }
      }
    }
    return RecordingRealRuntime(reVomanRuntime(recordingSessions), counts)
  }

  private fun assertCompleteSingleKickLifecycle(counts: LifecycleCounts) {
    assertThat(counts.sessionOpens).isEqualTo(1)
    assertThat(counts.childCreates).isEqualTo(1)
    assertThat(counts.childExecutes).isEqualTo(1)
    assertThat(counts.childCloses).isEqualTo(1)
    assertThat(counts.sessionCloses).isEqualTo(1)
  }

  private data class RecordingRealRuntime(
    val runtime: ReVomanRuntime,
    val counts: LifecycleCounts,
  )

  private class LifecycleCounts {
    var sessionOpens = 0
    var childCreates = 0
    var childExecutes = 0
    var childCloses = 0
    var sessionCloses = 0
  }

  private class RecordingSink : RunLogSink {
    val lines = mutableListOf<Pair<LogLevel, String>>()

    override fun line(level: LogLevel, message: String) {
      lines += level to message
    }

    override fun event(event: StepEvent) = Unit

    override fun close() = Unit
  }

  private class TraversalFailingMap(
    values: Map<String, Any?>,
    private val failure: Throwable,
  ) : LinkedHashMap<String, Any?>(values) {
    override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
      get() = throw failure
  }

  private class CountingSandboxFactory : SandboxFactory {
    var createCount = 0
    var closeCount = 0
    var executeCount = 0

    override fun create(): SandboxRuntime {
      createCount++
      return object : SandboxRuntime {
        override fun execute(
          script: String,
          target: ScriptTarget,
          context: PmExecutionContext,
          timeoutMs: Long,
        ): PmExecutionResult {
          executeCount++
          return PmExecutionResult(
            environment = context.environment.values,
            globals = context.globals.values,
            collectionVariables = context.collectionVariables.values,
            assertions = emptyList(),
            error = null,
          )
        }

        override fun close() {
          closeCount++
        }
      }
    }
  }
}
