/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.PostExeHook
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.input.config.runLogSink
import com.salesforce.revoman.input.config.step
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.log.LogLevel
import com.salesforce.revoman.output.log.RunLogSink
import com.salesforce.revoman.output.log.StepEvent
import com.salesforce.revoman.testing.http.MockHttpServer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * E2E proving the multi-kick [ReVoman.revUp] fold threads the FULL environment — values of every
 * type, not just [String] — from one kick into the next. External-network-free: a loopback
 * [MockHttpServer] answers the `{{baseUrl}}` steps so the run completes. The env values under test
 * are seeded via `dynamicEnvironment`, so they land in `rundown.mutableEnv` regardless of step
 * outcome; the assertions are purely about what kick N+1 inherits from kick N.
 */
class MultiKickEnvTypesE2ETest {
  @TempDir lateinit var temporaryDirectory: Path

  private val collection = "pm-templates/v3/cf-ledger-jump"

  private fun kick(seed: Map<String, Any?> = emptyMap()) =
    Kick.configure()
      .templatePath(collection)
      .dynamicEnvironment("baseUrl", baseUrl)
      .let { seed.entries.fold(it) { k, (key, value) -> k.dynamicEnvironment(key, value) } }
      .insecureHttp(true)
      .off()

  @Test
  fun `non-String env value produced by a kick is inherited typed by the next kick`() {
    // kick 1 seeds a non-String value (Int) into its own dynamicEnvironment; kick 2 has no seed.
    // The fold must carry `count` into kick 2's env AS AN Int — not stringified, not dropped.
    val rundowns = ReVoman.revUp(listOf(kick(mapOf("count" to 42)), kick()))

    assertThat(rundowns).hasSize(2)
    // Regression guard: kick 1 itself sees the typed value (baseline the fold must preserve).
    assertThat(rundowns[0].mutableEnv["count"]).isEqualTo(42)
    // The fix under test: kick 2 inherits the SAME Int, not a "42" String and not null.
    assertThat(rundowns[1].mutableEnv["count"]).isEqualTo(42)
    assertThat(rundowns[1].mutableEnv["count"]).isInstanceOf(Integer::class.java)
  }

  @Test
  fun `String env value still threads across kicks`() {
    val rundowns = ReVoman.revUp(listOf(kick(mapOf("token" to "abc")), kick()))

    assertThat(rundowns).hasSize(2)
    assertThat(rundowns[1].mutableEnv["token"]).isEqualTo("abc")
  }

  @Test
  fun `vararg primary carries post-hook mutation without recursing through public list`() {
    val typed = listOf(7, 8)
    val snapshots = mutableListOf<List<com.salesforce.revoman.output.Rundown>>()

    val rundowns =
      ReVoman.revUp(
        PostExeHook { current, accumulated ->
          snapshots += accumulated
          if (accumulated.size == 1) current.mutableEnv["typed"] = typed
        },
        emptyMap(),
        kick(),
        kick(),
      )

    assertThat(rundowns[1].mutableEnv["typed"]).isSameInstanceAs(typed)
    assertThat(snapshots.map(List<*>::size)).containsExactly(1, 2).inOrder()
  }

  @Test
  fun `concurrent public list calls isolate carry scopes capture and sinks`() {
    val collectionDirectory = copyIsolationCollection()
    val bothFirstChildrenClosed = CountDownLatch(2)
    val releaseHooks = CountDownLatch(1)
    val threadNumber = AtomicInteger()
    val executor =
      Executors.newFixedThreadPool(2) { task ->
        Thread(task, "revoman-concurrent-${threadNumber.incrementAndGet()}")
      }
    val stopId = "concurrent-a-stop"
    val jumpId = "concurrent-b-jump"
    val stopSink = IsolationSink()
    val jumpSink = IsolationSink()

    try {
      fun submit(id: String, sink: IsolationSink) =
        executor.submit<PublicCallResult> {
          val kick = isolationKick(collectionDirectory, id, sink)
          val rundowns =
            ReVoman.revUp(
              kicks = listOf(kick, kick),
              postExeHook =
                PostExeHook { current, accumulated ->
                  if (accumulated.size == 1) {
                    bothFirstChildrenClosed.countDown()
                    check(releaseHooks.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                      "timed out waiting to overlap public list calls"
                    }
                    current.mutableEnv["phase"] = "second"
                  }
                },
              dynamicEnvironment = emptyMap(),
            )
          PublicCallResult(Thread.currentThread().name, rundowns)
        }

      val stopFuture = submit(stopId, stopSink)
      val jumpFuture = submit(jumpId, jumpSink)
      assertThat(bothFirstChildrenClosed.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
      releaseHooks.countDown()

      val stop = stopFuture.get(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      val jump = jumpFuture.get(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      assertIsolatedPair(stop.rundowns, stopId, firstDirectiveJumps = false)
      assertIsolatedPair(jump.rundowns, jumpId, firstDirectiveJumps = true)
      assertIsolatedSink(stopSink, stopId, stop.threadName, foreignId = jumpId)
      assertIsolatedSink(jumpSink, jumpId, jump.threadName, foreignId = stopId)
    } finally {
      releaseHooks.countDown()
      executor.shutdownNow()
      executor.awaitTermination(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }
  }

  @Test
  // This E2E keeps nested public-call ordering inline so the re-entrancy boundary is explicit.
  @Suppress("LongMethod")
  fun `reentrant public runbook call restores outer state without a global observer`() {
    val collectionDirectory = copyIsolationCollection()
    val outerId = "reentrant-outer-stop"
    val nestedId = "reentrant-nested-jump"
    val outerStepOpened = CountDownLatch(1)
    val nestedChildOpened = CountDownLatch(1)
    val nestedHookEntered = CountDownLatch(1)
    val outerSink = IsolationSink(runbookStepOpened = outerStepOpened)
    val nestedSink = IsolationSink(childOpened = nestedChildOpened)
    val overriddenOuterKickSink = IsolationSink()
    val outerKick =
      isolationKick(
        collectionDirectory,
        outerId,
        overriddenOuterKickSink,
        autoAdvance = true,
      )
    val nestedKick = isolationKick(collectionDirectory, nestedId, nestedSink)
    var nestedRundowns: List<Rundown>? = null
    val runbook =
      Runbook("reentrant-isolation") {
        runLogSink = outerSink
        step {
          intent = "outer-first"
          phase = Phase.SETUP
          kick = outerKick
          assertAfter { _, _ ->
            check(outerStepOpened.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
              "outer runbook scope was not active before the nested public call"
            }
            nestedRundowns =
              ReVoman.revUp(
                kicks = listOf(nestedKick, nestedKick),
                postExeHook =
                  PostExeHook { current, accumulated ->
                    if (accumulated.size == 1) {
                      check(nestedChildOpened.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "nested child sink was not active"
                      }
                      nestedHookEntered.countDown()
                      current.mutableEnv["phase"] = "second"
                    }
                  },
                dynamicEnvironment = emptyMap(),
              )
            check(nestedHookEntered.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
              "nested public hook did not execute"
            }
          }
        }
        step {
          intent = "outer-second"
          phase = Phase.ACT
          kick = outerKick
        }
      }

    val outerRundowns = ReVoman.revUp(runbook)
    val nested = checkNotNull(nestedRundowns)

    assertIsolatedOuterRunbook(outerRundowns, outerId)
    assertIsolatedPair(nested, nestedId, firstDirectiveJumps = true)
    assertIsolatedSink(outerSink, outerId, Thread.currentThread().name, foreignId = nestedId)
    assertIsolatedSink(nestedSink, nestedId, Thread.currentThread().name, foreignId = outerId)
    assertThat(outerSink.events.filterIsInstance<StepEvent.RunbookStepStarted>()).hasSize(2)
    assertThat(outerSink.events.filterIsInstance<StepEvent.RunbookStepFinished>()).hasSize(2)
    assertThat(nestedSink.events.filterIsInstance<StepEvent.RunbookStepStarted>()).isEmpty()
    assertThat(nestedSink.events.filterIsInstance<StepEvent.RunbookStepFinished>()).isEmpty()
    assertThat(overriddenOuterKickSink.events).isEmpty()
    assertThat(overriddenOuterKickSink.lines).isEmpty()
  }

  private fun copyIsolationCollection(): Path {
    val destination = temporaryDirectory.resolve("session-isolation")
    Files.createDirectories(destination.resolve(".resources"))
    Files.writeString(destination.resolve(".resources/definition.yaml"), "\$kind: collection\n")
    listOf("a.request.yaml", "b.request.yaml").forEach { name ->
      requireNotNull(
          javaClass.classLoader.getResourceAsStream("pm-templates/v3/session-isolation/$name")
        )
        .use { source -> Files.copy(source, destination.resolve(name)) }
    }
    return destination
  }

  private fun isolationKick(
    collectionDirectory: Path,
    isolationId: String,
    sink: RunLogSink,
    autoAdvance: Boolean = false,
  ): Kick =
    Kick.configure()
      .templatePath(collectionDirectory.toString())
      .dynamicEnvironment("baseUrl", baseUrl)
      .dynamicEnvironment("isolationId", isolationId)
      .dynamicEnvironment("phase", "first")
      .dynamicEnvironment("autoAdvance", if (autoAdvance) "yes" else "no")
      .runLogSink(sink)
      .insecureHttp(true)
      .off()

  private fun assertIsolatedPair(
    rundowns: List<Rundown>,
    isolationId: String,
    firstDirectiveJumps: Boolean,
  ) {
    assertThat(rundowns).hasSize(2)
    val first = rundowns[0]
    val second = rundowns[1]
    val firstA = first.stepReports.first()
    val secondA = second.stepReports.first()

    assertThat(first.mutableEnv["isolationId"]).isEqualTo(isolationId)
    assertThat(first.mutableEnv["carriedEnvironment"]).isEqualTo("environment-$isolationId")
    assertThat(first.collectionVariables["childCollection"]).isEqualTo("collection-$isolationId")
    assertThat(first.globals["childGlobal"]).isEqualTo("global-$isolationId")
    assertThat(firstA.nextRequestSet).isTrue()
    assertThat(firstA.nextRequest).isEqualTo(if (firstDirectiveJumps) "b" else null)
    assertThat(first.stepReports).hasSize(if (firstDirectiveJumps) 2 else 1)

    assertThat(second.mutableEnv["isolationId"]).isEqualTo(isolationId)
    assertThat(second.mutableEnv["carriedEnvironment"]).isEqualTo("environment-$isolationId")
    assertThat(second.mutableEnv["bRan"]).isEqualTo("yes")
    assertThat(second.mutableEnv["collectionSeenByB"]).isEqualTo("absent")
    assertThat(second.mutableEnv["globalSeenByB"]).isEqualTo("absent")
    assertThat(second.collectionVariables.toMap()).isEmpty()
    assertThat(second.globals.toMap()).isEmpty()
    assertThat(secondA.nextRequestSet).isFalse()
    assertThat(secondA.nextRequest).isNull()
    assertThat(second.stepReports).hasSize(2)
  }

  private fun assertIsolatedOuterRunbook(rundowns: List<Rundown>, isolationId: String) {
    assertThat(rundowns).hasSize(2)
    val first = rundowns[0]
    val second = rundowns[1]

    assertThat(first.mutableEnv["isolationId"]).isEqualTo(isolationId)
    assertThat(first.mutableEnv["carriedEnvironment"]).isEqualTo("environment-$isolationId")
    assertThat(first.mutableEnv["phase"]).isEqualTo("second")
    assertThat(first.collectionVariables["childCollection"]).isEqualTo("collection-$isolationId")
    assertThat(first.globals["childGlobal"]).isEqualTo("global-$isolationId")
    assertThat(first.stepReports).hasSize(1)
    assertThat(first.stepReports.single().nextRequestSet).isTrue()
    assertThat(first.stepReports.single().nextRequest).isNull()

    assertThat(second.mutableEnv["isolationId"]).isEqualTo(isolationId)
    assertThat(second.mutableEnv["carriedEnvironment"]).isEqualTo("environment-$isolationId")
    assertThat(second.mutableEnv["collectionSeenByB"]).isEqualTo("absent")
    assertThat(second.mutableEnv["globalSeenByB"]).isEqualTo("absent")
    assertThat(second.collectionVariables.toMap()).isEmpty()
    assertThat(second.globals.toMap()).isEmpty()
    assertThat(second.stepReports).hasSize(2)
    assertThat(second.stepReports.first().nextRequestSet).isFalse()
  }

  private fun assertIsolatedSink(
    sink: IsolationSink,
    isolationId: String,
    expectedThread: String,
    foreignId: String,
  ) {
    val requestPaths =
      sink.events.filterIsInstance<StepEvent.StepFinished>().mapNotNull { it.requestPath }
    assertThat(requestPaths).isNotEmpty()
    assertThat(requestPaths.all { it.endsWith("/$isolationId") }).isTrue()
    assertThat(requestPaths.any { it.contains(foreignId) }).isFalse()
    assertThat(sink.threadNames).containsExactly(expectedThread)
    assertThat(sink.closeCount.get()).isEqualTo(0)
  }

  private data class PublicCallResult(val threadName: String, val rundowns: List<Rundown>)

  private class IsolationSink(
    private val childOpened: CountDownLatch? = null,
    private val runbookStepOpened: CountDownLatch? = null,
  ) : RunLogSink {
    val events = CopyOnWriteArrayList<StepEvent>()
    val lines = CopyOnWriteArrayList<String>()
    val threadNames = CopyOnWriteArraySet<String>()
    val closeCount = AtomicInteger()

    override fun line(level: LogLevel, message: String) {
      threadNames.add(Thread.currentThread().name)
      lines += message
    }

    override fun event(event: StepEvent) {
      threadNames.add(Thread.currentThread().name)
      events += event
      if (event is StepEvent.StepStarted) childOpened?.countDown()
      if (event is StepEvent.RunbookStepStarted) runbookStepOpened?.countDown()
    }

    override fun close() {
      closeCount.incrementAndGet()
    }
  }

  companion object {
    private const val LATCH_TIMEOUT_SECONDS = 20L

    private lateinit var fixture: MockHttpServer
    private lateinit var baseUrl: String

    @BeforeAll
    @JvmStatic
    fun startServer() {
      fixture = MockHttpServer.start { Response(OK).body("{}") }
      baseUrl = fixture.baseUrl
    }

    @AfterAll @JvmStatic fun stopServer() = fixture.close()
  }
}
