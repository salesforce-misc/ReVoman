/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.adapter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import performance.support.FakeHost

class WatcherLifecycleContractTest :
  FunSpec(
    {
      test("HID idle precedes caffeinate and the watcher encloses the terminal timed container") {
        FakeHost().use { host ->
          val events = host.repositoryRoot.resolve("watcher-order-events")
          val result =
            host.invoke(
              *captureCommand(host, "watcher-order").toTypedArray(),
              functionOverrides = lifecycleOverrides,
            )
          val timedIndex =
            result.commands.indexOfFirst { command ->
              "dev.revoman.performance.phase=timed" in command
            }
          val hidIndex = result.commands.indexOfFirst { it.firstOrNull() == "ioreg" }
          val caffeinateIndex = result.commands.indexOfFirst { it.firstOrNull() == "caffeinate" }

          result.exitCode shouldBe 0
          (hidIndex in 0 until caffeinateIndex) shouldBe true
          (caffeinateIndex in 0 until timedIndex) shouldBe true
          events.readLines() shouldBe
            listOf(
              "watcher-start",
              "timed-start",
              "timed-exit",
              "watcher-stop",
              "watcher-join",
              "caffeinate-stop",
              "caffeinate-join",
              "postflight",
            )
          result.commands.single { it.firstOrNull() == "caffeinate" }.also { invocation ->
            invocation shouldBe listOf("caffeinate", "-dims", "-w", invocation.last())
            ("-u" in invocation) shouldBe false
          }
        }
      }

      test("three consecutive CPU breaches permanently invalidate the watcher") {
        FakeHost().use { host ->
          val result =
            host.invokeFunction(
              "adapter_watcher_test_fixture",
              "69",
              "95",
              "68",
              "67",
              "66",
            )

          result.exitCode shouldBe 1
          result.standardOutput.trim() shouldBe "qualificationFailed:cpuIdle"
        }
      }

      test("the watcher permits its exact operation container and still rejects foreign containers") {
        FakeHost().use { host ->
          val token = "watcher-owned-container"
          val result =
            host.invoke(
              *captureCommand(host, token).toTypedArray(),
              environment =
                mapOf(
                  "FAKE_DOCKER_TIMED_CONTAINERS" to
                    "timed-1|$token|m4max-docker-linux-arm64-v1\n",
                ),
              functionOverrides = timedWindowOverrides,
            )

          result.exitCode shouldBe 0
        }
        FakeHost().use { host ->
          val result =
            host.invoke(
              *captureCommand(host, "watcher-foreign-container").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_DOCKER_TIMED_CONTAINERS" to
                    "foreign-1|someone-else|m4max-docker-linux-arm64-v1\n",
                ),
              functionOverrides = timedWindowOverrides,
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "QUALIFICATION_FAILED"
        }
      }

      test("the exact live software update identity appearing after preflight invalidates as update") {
        FakeHost().use { host ->
          val watcherDocument = host.repositoryRoot.resolve("captured-watcher.json")
          val result =
            host.invoke(
              *captureCommand(host, "watcher-new-software-update").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_PROCESS_DETAIL_ROW_WHILE_TIMED" to
                    liveSoftwareUpdateRow(
                      startIdentity = "Mon Aug 18 00:01:00 2026",
                      cpuPercent = "0.0",
                      memoryPercent = "0.0",
                    ),
                ),
              functionOverrides = timedWindowWithWatcherDocumentOverrides,
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "QUALIFICATION_FAILED"
          watcherDocument.exists() shouldBe true
          watcherDocument.readText().also { watcher ->
            watcher shouldContain "\"event\":\"update\""
            watcher shouldContain "\"observedSamples\":2"
            watcher shouldContain "\"terminalState\":\"qualificationFailed\""
          }
        }
      }

      test("an updater reusing a PID with a different start identity invalidates as a new update") {
        FakeHost().use { host ->
          val watcherDocument = host.repositoryRoot.resolve("captured-watcher.json")
          val result =
            host.invoke(
              *captureCommand(host, "watcher-reused-updater-pid").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_PROCESS_LIST" to liveSoftwareUpdateCommand,
                  "FAKE_PROCESS_DETAIL_ROW" to
                    liveSoftwareUpdateRow(
                      startIdentity = "Mon Aug 18 00:00:00 2026",
                      cpuPercent = "0.0",
                      memoryPercent = "0.0",
                    ),
                  "FAKE_PROCESS_DETAIL_ROW_WHILE_TIMED" to
                    liveSoftwareUpdateRow(
                      startIdentity = "Mon Aug 18 00:01:00 2026",
                      cpuPercent = "0.0",
                      memoryPercent = "0.0",
                    ),
                ),
              functionOverrides = timedWindowWithWatcherDocumentOverrides,
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "QUALIFICATION_FAILED"
          watcherDocument.exists() shouldBe true
          watcherDocument.readText().also { watcher ->
            watcher shouldContain "\"event\":\"update\""
            watcher shouldContain "\"observedSamples\":2"
            watcher shouldContain "\"terminalState\":\"qualificationFailed\""
          }
        }
      }

      test("three sustained active samples from the exact pre-existing live updater invalidate as update") {
        FakeHost().use { host ->
          val watcherDocument = host.repositoryRoot.resolve("captured-watcher.json")
          val result =
            host.invoke(
              *captureCommand(host, "watcher-sustained-software-update").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_PROCESS_LIST" to liveSoftwareUpdateCommand,
                  "FAKE_PROCESS_DETAIL_ROW" to
                    liveSoftwareUpdateRow(
                      startIdentity = "Mon Aug 18 00:00:00 2026",
                      cpuPercent = "0.0",
                      memoryPercent = "0.0",
                    ),
                  "FAKE_PROCESS_DETAIL_ROW_WHILE_TIMED" to
                    liveSoftwareUpdateRow(
                      startIdentity = "Mon Aug 18 00:00:00 2026",
                      cpuPercent = "100",
                      memoryPercent = "30",
                    ),
                ),
              functionOverrides = timedWindowWithWatcherDocumentOverrides,
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "QUALIFICATION_FAILED"
          watcherDocument.exists() shouldBe true
          watcherDocument.readText().also { watcher ->
            watcher shouldContain "\"event\":\"update\""
            watcher shouldContain "\"observedSamples\":4"
            watcher shouldContain "\"terminalState\":\"qualificationFailed\""
          }
        }
      }

      test("watcher backup and process probe failures invalidate instead of flowing through grep") {
        listOf(
            "FAKE_BACKUP_PROBE_FAIL_WHILE_TIMED",
            "FAKE_PROCESS_PROBE_FAIL_WHILE_TIMED",
          )
          .forEach { failureVariable ->
            FakeHost().use { host ->
              val probeEvents = host.repositoryRoot.resolve("watcher-probe-events")
              val result =
                host.invoke(
                  *captureCommand(host, "watcher-${failureVariable.lowercase()}").toTypedArray(),
                  environment = mapOf(failureVariable to "1"),
                  functionOverrides = probeObservationOverrides,
                )

              result.exitCode shouldBe 2
              probeEvents.readLines().none { event -> event.startsWith("timed|0|") } shouldBe true
            }
          }
      }

      test("three sustained high-memory process samples permanently invalidate the watcher") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *captureCommand(host, "watcher-process-memory").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_PROCESS_DETAIL_ROW" to
                    "77 Mon Aug 18 00:00:00 2026 1 30 /usr/bin/ordinary-task",
                ),
              functionOverrides = timedWindowOverrides,
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "QUALIFICATION_FAILED"
        }
      }

      test("caffeinate must remain alive before the first timed phase") {
        FakeHost().use { host ->
          val result =
            host.invoke(
              *captureCommand(host, "caffeinate-startup-failure").toTypedArray(),
              environment = mapOf("FAKE_CAFFEINATE_EXIT_CODE" to "23"),
            )

          result.exitCode shouldBe 2
          result.commands.none { "dev.revoman.performance.phase=timed" in it } shouldBe true
        }
      }

      test("the bounded command helper terminates a hung child and joins its timer") {
        FakeHost().use { host ->
          val result =
            host.invokeFunction(
              "adapter_run_bounded_command",
              "20",
              "/bin/sleep",
              "1",
            )

          result.exitCode shouldBe 124
        }
      }

      test("ordinary and signal exits stop and join controller children before postflight") {
        listOf("ordinary", "INT", "TERM", "HUP").forEach { exitKind ->
          FakeHost().use { host ->
            val events = host.repositoryRoot.resolve("watcher-$exitKind-events")
            val result =
              host.invoke(
                *captureCommand(host, "watcher-${exitKind.lowercase()}").toTypedArray(),
                environment = mapOf("FAKE_EXIT_KIND" to exitKind),
                functionOverrides = lifecycleOverrides,
              )

            result.exitCode shouldBe if (exitKind == "ordinary") 0 else 2
            events.readLines() shouldBe
              listOf(
                "watcher-start",
                "timed-start",
                "timed-exit",
                "watcher-stop",
                "watcher-join",
                "caffeinate-stop",
                "caffeinate-join",
                "postflight",
              )
          }
        }
      }
    },
  )

private val lifecycleOverrides =
  """
  eval "${'$'}(declare -f adapter_start_watcher | /usr/bin/sed '1s/adapter_start_watcher/adapter_start_watcher_impl/')"
  eval "${'$'}(declare -f adapter_write_postflight | /usr/bin/sed '1s/adapter_write_postflight/adapter_write_postflight_impl/')"
  adapter_start_watcher() {
    printf '%s\n' watcher-start >>"${'$'}ADAPTER_REPO_ROOT/watcher-${'$'}{FAKE_EXIT_KIND:-order}-events"
    adapter_start_watcher_impl "${'$'}@"
  }
  adapter_run_work_phase() {
    printf '%s\n' timed-start >>"${'$'}ADAPTER_REPO_ROOT/watcher-${'$'}{FAKE_EXIT_KIND:-order}-events"
    if [ "${'$'}{FAKE_EXIT_KIND:-ordinary}" != ordinary ] && [ "${'$'}{FAKE_EXIT_KIND:-order}" != order ]; then
      /bin/kill -s "${'$'}FAKE_EXIT_KIND" "${'$'}${'$'}"
    else
      command docker "${'$'}{ADAPTER_DOCKER_CONTEXT_ARGS[@]}" run \
        --label dev.revoman.performance.phase=timed \
        --label "dev.revoman.performance.operation=${'$'}ADAPTER_RUN_TOKEN" \
        --label "dev.revoman.performance.profile=${'$'}ADAPTER_SUBSTRATE_PROFILE"
    fi
    printf '%s\n' timed-exit >>"${'$'}ADAPTER_REPO_ROOT/watcher-${'$'}{FAKE_EXIT_KIND:-order}-events"
    return 0
  }
  adapter_stop_and_join_controller_children() {
    event_file="${'$'}ADAPTER_REPO_ROOT/watcher-${'$'}{FAKE_EXIT_KIND:-order}-events"
    if [ "${'$'}ADAPTER_WATCHER_STARTED" -eq 1 ]; then
      printf '%s\n' watcher-stop >>"${'$'}event_file"
      : >"${'$'}ADAPTER_QUALIFICATION_ROOT/.watcher-stop"
      wait "${'$'}ADAPTER_WATCHER_PID" || return 1
      ADAPTER_WATCHER_STARTED=0
      printf '%s\n' watcher-join >>"${'$'}event_file"
    fi
    if [ -n "${'$'}ADAPTER_CAFFEINATE_PID" ]; then
      printf '%s\n' caffeinate-stop >>"${'$'}event_file"
      /bin/kill "${'$'}ADAPTER_CAFFEINATE_PID" 2>/dev/null || true
      wait "${'$'}ADAPTER_CAFFEINATE_PID" 2>/dev/null || true
      ADAPTER_CAFFEINATE_PID=''
      printf '%s\n' caffeinate-join >>"${'$'}event_file"
    fi
    return 0
  }
  adapter_write_postflight() {
    printf '%s\n' postflight >>"${'$'}ADAPTER_REPO_ROOT/watcher-${'$'}{FAKE_EXIT_KIND:-order}-events"
    adapter_write_postflight_impl "${'$'}@"
  }
  """.trimIndent()

private val timedWindowOverrides =
  """
  adapter_sleep_millis() { /bin/sleep 0.001; }
  adapter_run_work_phase() {
    : >"${'$'}ADAPTER_REPO_ROOT/.fake-timed-running"
    attempts=0
    while [ ! -e "${'$'}ADAPTER_REPO_ROOT/.fake-timed-observed" ] && [ "${'$'}attempts" -lt 1000 ]; do
      /bin/sleep 0.010
      attempts=${'$'}((attempts + 1))
    done
    if [ ! -e "${'$'}ADAPTER_REPO_ROOT/.fake-timed-observed" ]; then
      return 1
    fi
    attempts=0
    while [ ! -e "${'$'}ADAPTER_QUALIFICATION_ROOT/.watcher-failed" ] &&
      [ "${'$'}(/usr/bin/awk 'END { print NR }' "${'$'}ADAPTER_QUALIFICATION_ROOT/.watcher-observations")" -lt 4 ] &&
      [ "${'$'}attempts" -lt 1000 ]; do
      /bin/sleep 0.001
      attempts=${'$'}((attempts + 1))
    done
    /bin/rm -f "${'$'}ADAPTER_REPO_ROOT/.fake-timed-running"
    return 0
  }
  """.trimIndent()

private val timedWindowWithWatcherDocumentOverrides =
  """
  $timedWindowOverrides
  eval "${'$'}(declare -f adapter_write_watcher_document | /usr/bin/sed '1s/adapter_write_watcher_document/adapter_write_watcher_document_impl/')"
  adapter_write_watcher_document() {
    adapter_write_watcher_document_impl "${'$'}@" || return 1
    /bin/cp "${'$'}ADAPTER_QUALIFICATION_ROOT/watcher.json" "${'$'}ADAPTER_REPO_ROOT/captured-watcher.json"
  }
  """.trimIndent()

private val probeObservationOverrides =
  """
  $timedWindowOverrides
  eval "${'$'}(declare -f adapter_watcher_observation | /usr/bin/sed '1s/adapter_watcher_observation/adapter_watcher_observation_impl/')"
  adapter_watcher_observation() {
    output="${'$'}(adapter_watcher_observation_impl)"
    observation_status="${'$'}?"
    if [ -e "${'$'}ADAPTER_REPO_ROOT/.fake-timed-running" ]; then
      printf 'timed|%s|%s\n' "${'$'}observation_status" "${'$'}output" >>"${'$'}ADAPTER_REPO_ROOT/watcher-probe-events"
    fi
    [ "${'$'}observation_status" -eq 0 ] && printf '%s\n' "${'$'}output"
    return "${'$'}observation_status"
  }
  """.trimIndent()

private fun captureCommand(host: FakeHost, token: String): List<String> =
  listOf(
    "capture",
    "--profile",
    "warm",
    "--forks",
    "10",
    "--host-id",
    "opaque-host",
    "--session-id",
    "session-1",
    "--sequence",
    "1",
    "--distribution",
    host.frozenDistribution("$token-distribution").toString(),
    "--output",
    host.output(token),
  )

private const val liveSoftwareUpdateCommand =
  "/System/Library/CoreServices/Software Update.app/Contents/Resources/softwareupdated"

private fun liveSoftwareUpdateRow(
  startIdentity: String,
  cpuPercent: String,
  memoryPercent: String,
): String = "696 $startIdentity $cpuPercent $memoryPercent $liveSoftwareUpdateCommand"
