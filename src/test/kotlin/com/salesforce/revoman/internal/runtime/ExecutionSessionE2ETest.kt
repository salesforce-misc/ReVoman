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
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.input.config.step
import com.salesforce.revoman.internal.log.RunLogContext
import com.salesforce.revoman.internal.postman.sandbox.PmSandbox
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.log.LogLevel
import com.salesforce.revoman.output.log.RunLogSink
import com.salesforce.revoman.output.log.StepEvent
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ExecutionSessionE2ETest {
  @TempDir lateinit var temporaryDirectory: Path

  @Test
  fun `real repeated kick carries only environment across isolated children`() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      val body = "{}".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    try {
      val collectionDirectory = temporaryDirectory.resolve("session-isolation")
      Files.createDirectories(collectionDirectory.resolve(".resources"))
      Files.writeString(
        collectionDirectory.resolve(".resources/definition.yaml"),
        "\$kind: collection\n",
      )
      listOf("a.request.yaml", "b.request.yaml").forEach { name ->
        requireNotNull(
            javaClass.classLoader.getResourceAsStream("pm-templates/v3/session-isolation/$name")
          )
          .use { source -> Files.copy(source, collectionDirectory.resolve(name)) }
      }
      val kick =
        Kick.configure()
          .templatePath(collectionDirectory.toString())
          .dynamicEnvironment("baseUrl", "http://127.0.0.1:${server.address.port}")
          .dynamicEnvironment("isolationId", "legacy")
          .dynamicEnvironment("phase", "first")
          .insecureHttp(true)
          .off()
      var hookCalls = 0
      val results =
        reVomanRuntime(SandboxFactory { PmSandbox() })
          .execute(
            listOf(kick, kick),
            PostExeHook { current, _ ->
              hookCalls++
              if (hookCalls == 1) current.mutableEnv["phase"] = "second"
            },
            emptyMap(),
          )

      assertThat(results.map { it.stepReports.size }).containsExactly(1, 2).inOrder()
      assertThat(results[1].mutableEnv["carriedEnvironment"]).isEqualTo("from-first-child")
      assertThat(results[1].mutableEnv["bRan"]).isEqualTo("yes")
      assertThat(results[1].mutableEnv["collectionSeenByB"]).isEqualTo("absent")
      assertThat(results[1].mutableEnv["globalSeenByB"]).isEqualTo("absent")
      assertThat(results[0].collectionVariables["childCollection"]).isEqualTo("first-child-only")
      assertThat(results[0].globals["childGlobal"]).isEqualTo("first-child-only")
      assertThat(results[1].collectionVariables.toMap()).isEmpty()
      assertThat(results[1].globals.toMap()).isEmpty()
      assertThat(results[0].mutableEnv.toMap()).containsEntry("phase", "second")
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `runbook sink matrix borrows overrides masks and restores exact contexts`() {
    val ambient = TrackingSink("ambient")
    val runbookSink = TrackingSink("runbook")
    val kickSink = TrackingSink("kick")
    val events = mutableListOf<String>()
    val sessions =
      executionSessionFactory(
        KickExecutionFactory { kick, environment ->
          object : KickExecution {
            override val configuredKick = kick
            override val effectiveDynamicEnvironment = environment
            override val scripts: ScriptExecutor
              get() = error("unused")

            override val sandboxInitialized = false

            override fun execute(): Rundown {
              events += "execute:${activeSinkName(ambient, runbookSink, kickSink)}"
              return rundown(environment)
            }

            override fun close() {
              events += "child-close:${activeSinkName(ambient, runbookSink, kickSink)}"
            }
          }
        }
      )
    val runtime = reVomanRuntime(sessions)
    RunLogContext.install(ambient)
    try {
      val realSinkRunbook =
        Runbook.configure()
          .runLogSink(runbookSink)
          .step(
            "real-runbook-sink",
            Phase.ACT,
            Kick.configure().runLogSink(kickSink).off(),
          ) { step ->
            step.assertAfter { _, _ ->
              events += "assert:${activeSinkName(ambient, runbookSink, kickSink)}"
            }
          }
          .off()
      runtime.execute(realSinkRunbook, emptyMap())
      events += "restored:${activeSinkName(ambient, runbookSink, kickSink)}"

      val kickSinkRunbook =
        Runbook.configure()
          .step("kick-sink", Phase.ACT, Kick.configure().runLogSink(kickSink).off()) { step ->
            step.assertAfter { _, _ ->
              events += "assert:${activeSinkName(ambient, runbookSink, kickSink)}"
            }
          }
          .off()
      runtime.execute(kickSinkRunbook, emptyMap())
      events += "restored:${activeSinkName(ambient, runbookSink, kickSink)}"

      val noOpRunbook =
        Runbook.configure()
          .step("no-op", Phase.ACT, Kick.configure().off()) { step ->
            step.assertAfter { _, _ ->
              events += "assert:${activeSinkName(ambient, runbookSink, kickSink)}"
            }
          }
          .off()
      runtime.execute(noOpRunbook, emptyMap())
      events += "restored:${activeSinkName(ambient, runbookSink, kickSink)}"
    } finally {
      RunLogContext.remove()
    }

    assertThat(events)
      .containsExactly(
        "execute:runbook",
        "child-close:runbook",
        "assert:runbook",
        "restored:ambient",
        "execute:kick",
        "child-close:kick",
        "assert:ambient",
        "restored:ambient",
        "execute:no-op",
        "child-close:no-op",
        "assert:ambient",
        "restored:ambient",
      )
      .inOrder()
    assertThat(ambient.closeCount).isEqualTo(0)
    assertThat(runbookSink.closeCount).isEqualTo(0)
    assertThat(kickSink.closeCount).isEqualTo(0)
    assertThat(runbookSink.observedContexts).isNotEmpty()
    assertThat(runbookSink.observedContexts).doesNotContain("different")
  }

  @Test
  fun `throwing runbook restores ambient context and never closes borrowed sinks`() {
    val ambient = TrackingSink("ambient")
    val runbookSink = TrackingSink("runbook")
    val kickSink = TrackingSink("kick")
    val failure = AssertionError("assertion")
    val runtime =
      reVomanRuntime(
        executionSessionFactory(
          KickExecutionFactory { kick, environment ->
            recordingChild(mutableListOf(), kick, environment, rundown(environment))
          }
        )
      )
    val runbook =
      Runbook.configure()
        .runLogSink(runbookSink)
        .step("fails", Phase.ACT, Kick.configure().runLogSink(kickSink).off()) { step ->
          step.assertAfter { _, _ ->
            assertThat(RunLogContext.current()).isSameInstanceAs(runbookSink)
            throw failure
          }
        }
        .off()
    RunLogContext.install(ambient)
    try {
      val thrown = assertThrows<AssertionError> { runtime.execute(runbook, emptyMap()) }
      assertThat(thrown).isSameInstanceAs(failure)
      assertThat(RunLogContext.current()).isSameInstanceAs(ambient)
    } finally {
      RunLogContext.remove()
    }

    assertThat(ambient.closeCount).isEqualTo(0)
    assertThat(runbookSink.closeCount).isEqualTo(0)
    assertThat(kickSink.closeCount).isEqualTo(0)
    assertThat(runbookSink.observedContexts).isNotEmpty()
    assertThat(runbookSink.observedContexts).doesNotContain("different")
  }

  @Test
  fun `empty list and empty runbook each open and close one zero-child session`() {
    val events = mutableListOf<String>()
    val runtime = reVomanRuntime(recordingSessions(events) { _, _ -> error("no child expected") })

    val listResult = runtime.execute(emptyList(), NO_OP_HOOK, mapOf("seed" to 1))
    val runbookResult = runtime.execute(Runbook {}, mapOf("seed" to 2))

    assertThat(listResult).isEmpty()
    assertThat(runbookResult).isEmpty()
    assertThat(events)
      .containsExactly(
        "session-open:{seed=1}",
        "session-close",
        "session-open:{}",
        "session-close",
      )
      .inOrder()
  }

  @Test
  fun `list owns one session and distinct sequential children for duplicate occurrences`() {
    val events = mutableListOf<String>()
    val duplicate = Kick.configure().dynamicEnvironment("name", "duplicate").off()
    val third = Kick.configure().dynamicEnvironment("name", "third").off()
    var index = 0
    val runtime =
      reVomanRuntime(
        recordingSessions(events) { kick, environment ->
          index++
          recordingChild(events, kick, environment, rundown(environment + ("index" to index)))
        }
      )

    val results = runtime.execute(listOf(duplicate, duplicate, third), NO_OP_HOOK, emptyMap())

    assertThat(results.map { it.mutableEnv["index"] }).containsExactly(1, 2, 3).inOrder()
    assertThat(events)
      .containsExactly(
        "session-open:{}",
        "child-create:duplicate",
        "child-execute:duplicate",
        "child-close:duplicate",
        "child-create:duplicate",
        "child-execute:duplicate",
        "child-close:duplicate",
        "child-create:third",
        "child-execute:third",
        "child-close:third",
        "session-close",
      )
      .inOrder()
  }

  @Test
  fun `runbook owns one session and distinct sequential children for duplicate kicks`() {
    val events = mutableListOf<String>()
    val duplicate =
      Kick.configure().templatePath("duplicate").dynamicEnvironment("name", "duplicate").off()
    val third = Kick.configure().templatePath("third").dynamicEnvironment("name", "third").off()
    val runtime =
      reVomanRuntime(
        recordingSessions(events) { kick, environment ->
          recordingChild(events, kick, environment, rundown(environment))
        }
      )
    val runbook = Runbook {
      step {
        intent = "first"
        phase = Phase.SETUP
        kick = duplicate
      }
      step {
        intent = "second"
        phase = Phase.ACT
        kick = duplicate
      }
      step {
        intent = "third"
        phase = Phase.ASSERT
        kick = third
      }
    }

    val result = runtime.execute(runbook, emptyMap())

    assertThat(result).hasSize(3)
    assertThat(events)
      .containsExactly(
        "session-open:{}",
        "child-create:duplicate",
        "child-execute:duplicate",
        "child-close:duplicate",
        "child-create:duplicate",
        "child-execute:duplicate",
        "child-close:duplicate",
        "child-create:third",
        "child-execute:third",
        "child-close:third",
        "session-close",
      )
      .inOrder()
  }

  @Test
  fun `list callback snapshots are frozen and post-hook fresh carry preserves value types`() {
    val effectiveEnvironments = mutableListOf<Map<String, Any?>>()
    val rundowns = mutableListOf<Rundown>()
    val callbackSnapshots = mutableListOf<List<Rundown>>()
    val firstRetainedSize = mutableListOf<Int>()
    val runtime =
      reVomanRuntime(
        executionSessionFactory(
          KickExecutionFactory { kick, environment ->
            effectiveEnvironments += environment
            val result = rundown(environment)
            rundowns += result
            recordingChild(mutableListOf(), kick, environment, result)
          }
        )
      )
    val first = Kick.configure().dynamicEnvironment("shared", "first-configured").off()
    val second = Kick.configure().dynamicEnvironment("shared", "second-configured").off()
    val third = Kick.configure().off()
    val typed = listOf(1, 2, 3)
    val hook = PostExeHook { current, accumulated ->
      callbackSnapshots += accumulated
      if (callbackSnapshots.size == 1) {
        current.immutableEnv
        current.mutableEnv.immutableEnv
        current.mutableEnv["shared"] = "post-hook"
        current.mutableEnv["typed"] = typed
      } else if (callbackSnapshots.size == 2) {
        firstRetainedSize += callbackSnapshots.first().size
        rundowns.first().mutableEnv["shared"] = "late-old-mutation"
        rundowns.first().mutableEnv["typed"] = listOf(99)
      }
    }

    runtime.execute(listOf(first, second, third), hook, mapOf("initial" to 42))

    assertThat(effectiveEnvironments[0])
      .containsAtLeast("initial", 42, "shared", "first-configured")
    assertThat(effectiveEnvironments[1])
      .containsAtLeast("initial", 42, "shared", "post-hook", "typed", typed)
    assertThat(effectiveEnvironments[2]["shared"]).isEqualTo("post-hook")
    assertThat(effectiveEnvironments[2]["typed"]).isSameInstanceAs(typed)
    assertThat(callbackSnapshots.map(List<Rundown>::size)).containsExactly(1, 2, 3).inOrder()
    assertThat(firstRetainedSize).containsExactly(1)
  }

  @Test
  fun `throwing list hook keeps primary identity and directly suppresses session close failure`() {
    val hookFailure = AssertionError("hook")
    val closeFailure = IllegalStateException("session-close")
    var childCloses = 0
    var sessionCloses = 0
    val sessions = ExecutionSessionFactory { initial ->
      val delegate =
        executionSession(
          initial,
          KickExecutionFactory { kick, environment ->
            object :
              KickExecution by recordingChild(
                mutableListOf(),
                kick,
                environment,
                rundown(environment),
              ) {
              override fun close() {
                childCloses++
              }
            }
          },
        )
      object : ExecutionSession by delegate {
        override fun close() {
          sessionCloses++
          delegate.close()
          throw closeFailure
        }
      }
    }

    val thrown =
      assertThrows<AssertionError> {
        reVomanRuntime(sessions)
          .execute(
            listOf(Kick.configure().off()),
            PostExeHook { _, _ ->
              assertThat(childCloses).isEqualTo(1)
              throw hookFailure
            },
            emptyMap(),
          )
      }

    assertThat(thrown).isSameInstanceAs(hookFailure)
    assertThat(thrown.suppressed.asList()).containsExactly(closeFailure)
    assertThat(childCloses).isEqualTo(1)
    assertThat(sessionCloses).isEqualTo(1)
  }

  @Test
  fun `runbook freezes next environment before assertAfter mutation`() {
    val effectiveEnvironments = mutableListOf<Map<String, Any?>>()
    val results =
      listOf(
        rundown(mapOf("phase" to "before-assert", "typed" to 7)),
        rundown(mapOf("phase" to "second")),
      )
    var index = 0
    val runtime =
      reVomanRuntime(
        executionSessionFactory(
          KickExecutionFactory { kick, environment ->
            effectiveEnvironments += environment
            recordingChild(mutableListOf(), kick, environment, results[index++])
          }
        )
      )
    val runbook = Runbook {
      step {
        intent = "first"
        phase = Phase.SETUP
        kick = Kick.configure().off()
        produces("phase")
        assertAfter { rundown, _ ->
          rundown.immutableEnv
          rundown.mutableEnv.immutableEnv
          rundown.mutableEnv["phase"] = "after-assert"
          rundown.mutableEnv["typed"] = 99
        }
      }
      step {
        intent = "second"
        phase = Phase.ACT
        kick = Kick.configure().dynamicEnvironment("phase", "configured").off()
        consumes("phase", "typed")
      }
    }

    val returned = runtime.execute(runbook, mapOf("initial" to "caller"))

    assertThat(returned).hasSize(2)
    assertThat(effectiveEnvironments[0]["initial"]).isEqualTo("caller")
    assertThat(effectiveEnvironments[1]["phase"]).isEqualTo("before-assert")
    assertThat(effectiveEnvironments[1]["typed"]).isEqualTo(7)
  }

  @Test
  fun `runbook failures close children and outer session with exact suppression`() {
    val cases =
      listOf(
        FailureCase("body", IllegalStateException("body"), FailurePoint.BODY),
        FailureCase("produces", AssertionError("unused"), FailurePoint.PRODUCES),
        FailureCase("assert", AssertionError("assert"), FailurePoint.ASSERT_AFTER),
      )

    cases.forEach { case ->
      val closeFailure = IllegalArgumentException("${case.name}-session-close")
      var childCloses = 0
      var sessionCloses = 0
      val returned = rundown(mapOf("actual" to "value"))
      val sessions = ExecutionSessionFactory { initial ->
        val delegate =
          executionSession(
            initial,
            KickExecutionFactory { kick, environment ->
              object : KickExecution {
                override val configuredKick = kick
                override val effectiveDynamicEnvironment = environment
                override val scripts: ScriptExecutor
                  get() = error("unused")

                override val sandboxInitialized = false

                override fun execute(): Rundown {
                  if (case.point == FailurePoint.BODY) throw case.failure
                  return returned
                }

                override fun close() {
                  childCloses++
                }
              }
            },
          )
        object : ExecutionSession by delegate {
          override fun close() {
            sessionCloses++
            delegate.close()
            throw closeFailure
          }
        }
      }
      val runbook = Runbook {
        step {
          intent = case.name
          phase = Phase.ACT
          kick = Kick.configure().off()
          if (case.point == FailurePoint.PRODUCES) produces("missing")
          if (case.point == FailurePoint.ASSERT_AFTER) {
            assertAfter { _, _ -> throw case.failure }
          }
        }
      }

      val thrown = assertThrows<Throwable> { reVomanRuntime(sessions).execute(runbook, emptyMap()) }

      when (case.point) {
        FailurePoint.PRODUCES -> assertThat(thrown).isInstanceOf(AssertionError::class.java)
        else -> assertThat(thrown).isSameInstanceAs(case.failure)
      }
      assertThat(thrown.suppressed.asList()).containsExactly(closeFailure)
      assertThat(childCloses).isEqualTo(1)
      assertThat(sessionCloses).isEqualTo(1)
    }
  }

  @Test
  fun `runbook consumes failure closes its zero-child session`() {
    var creates = 0
    var closes = 0
    val sessions = ExecutionSessionFactory { initial ->
      val delegate =
        executionSession(
          initial,
          KickExecutionFactory { _, _ ->
            creates++
            error("must not create")
          },
        )
      object : ExecutionSession by delegate {
        override fun close() {
          closes++
          delegate.close()
        }
      }
    }
    val runbook = Runbook {
      step {
        intent = "needs-value"
        phase = Phase.ACT
        kick = Kick.configure().off()
        consumes("missing")
      }
    }

    assertThrows<AssertionError> { reVomanRuntime(sessions).execute(runbook, emptyMap()) }

    assertThat(creates).isEqualTo(0)
    assertThat(closes).isEqualTo(1)
  }

  private fun recordingSessions(
    events: MutableList<String>,
    createChild: (Kick, Map<String, Any?>) -> KickExecution,
  ): ExecutionSessionFactory = ExecutionSessionFactory { initial ->
    events += "session-open:$initial"
    val delegate = executionSession(initial, KickExecutionFactory(createChild))
    object : ExecutionSession by delegate {
      override fun close() {
        events += "session-close"
        delegate.close()
      }
    }
  }

  private fun recordingChild(
    events: MutableList<String>,
    kick: Kick,
    environment: Map<String, Any?>,
    result: Rundown,
  ): KickExecution {
    val name = kick.templatePaths().singleOrNull() ?: kick.dynamicEnvironment()["name"] ?: "unnamed"
    events += "child-create:$name"
    return object : KickExecution {
      override val configuredKick: Kick = kick
      override val effectiveDynamicEnvironment: Map<String, Any?> = environment
      override val scripts: ScriptExecutor
        get() = error("scripts are not used")

      override val sandboxInitialized = false

      override fun execute(): Rundown {
        events += "child-execute:$name"
        return result
      }

      override fun close() {
        events += "child-close:$name"
      }
    }
  }

  private fun rundown(values: Map<String, Any?>): Rundown =
    Rundown(
      mutableEnv = PostmanEnvironment(values.toMutableMap()),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 0,
      collectionVariables = PostmanEnvironment(mutableMapOf("collection" to "readable")),
      globals = PostmanEnvironment(mutableMapOf("global" to "readable")),
    )

  private fun activeSinkName(vararg known: TrackingSink): String =
    when (val current = RunLogContext.current()) {
      RunLogSink.NoOp -> "no-op"
      else -> known.firstOrNull { it === current }?.name ?: "unknown"
    }

  private class TrackingSink(val name: String) : RunLogSink {
    val observedContexts = mutableListOf<String>()
    var closeCount = 0

    override fun line(level: LogLevel, message: String) {
      observedContexts += nameOfCurrent()
    }

    override fun event(event: StepEvent) {
      observedContexts += nameOfCurrent()
    }

    override fun close() {
      closeCount++
    }

    private fun nameOfCurrent(): String =
      if (RunLogContext.current() === this) name else "different"
  }

  private data class FailureCase(
    val name: String,
    val failure: Throwable,
    val point: FailurePoint,
  )

  private enum class FailurePoint {
    BODY,
    PRODUCES,
    ASSERT_AFTER,
  }

  private companion object {
    val NO_OP_HOOK = PostExeHook { _, _ -> }
  }
}
