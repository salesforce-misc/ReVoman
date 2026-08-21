/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.adapter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import performance.hash.Sha256
import performance.support.FakeHost

class OperationLockContractTest :
  FunSpec(
    {
      test("the private host profile lock encloses runtime work reservation and publication") {
        FakeHost().use { host ->
          val privateTmp = host.repositoryRoot.resolve("private-tmp").also { it.createDirectories() }
          val events = host.repositoryRoot.resolve("lock-events")
          val observedLock = host.repositoryRoot.resolve("observed-lock")
          val observedMode = host.repositoryRoot.resolve("observed-lock-mode")
          val result =
            host.invoke(
              *captureCommand(host, "lock-order").toTypedArray(),
              environment = mapOf("TMPDIR" to privateTmp.toString()),
              functionOverrides =
                """
                eval "${'$'}(declare -f adapter_acquire_operation_lock | /usr/bin/sed '1s/adapter_acquire_operation_lock/adapter_acquire_operation_lock_impl/')"
                eval "${'$'}(declare -f adapter_release_operation_lock | /usr/bin/sed '1s/adapter_release_operation_lock/adapter_release_operation_lock_impl/')"
                adapter_acquire_operation_lock() {
                  printf '%s\n' lock >>"${'$'}ADAPTER_REPO_ROOT/lock-events"
                  adapter_acquire_operation_lock_impl "${'$'}@"
                }
                adapter_release_operation_lock() {
                  printf '%s\n' unlock >>"${'$'}ADAPTER_REPO_ROOT/lock-events"
                  adapter_release_operation_lock_impl "${'$'}@"
                }
                adapter_prepare_runtime() {
                  printf '%s\n' runtime >>"${'$'}ADAPTER_REPO_ROOT/lock-events"
                  printf '%s\n' "${'$'}{ADAPTER_OPERATION_LOCK_PATH-}" >"${'$'}ADAPTER_REPO_ROOT/observed-lock"
                  if [ -n "${'$'}{ADAPTER_OPERATION_LOCK_PATH-}" ]; then
                    /usr/bin/stat -f '%Lp' "${'$'}ADAPTER_OPERATION_LOCK_PATH" >"${'$'}ADAPTER_REPO_ROOT/observed-lock-mode"
                  fi
                  return 0
                }
                adapter_prepare_finalizer() {
                  printf '%s\n' recovery-and-verification >>"${'$'}ADAPTER_REPO_ROOT/lock-events"
                  return 0
                }
                adapter_reserve_output() {
                  printf '%s\n' reserve >>"${'$'}ADAPTER_REPO_ROOT/lock-events"
                  ADAPTER_RESERVED=1
                  return 0
                }
                adapter_execute_checkpoint() {
                  printf '%s\n' publication >>"${'$'}ADAPTER_REPO_ROOT/lock-events"
                  return 0
                }
                """.trimIndent(),
            )

          result.exitCode shouldBe 0
          events.readLines() shouldBe
            listOf("lock", "runtime", "recovery-and-verification", "reserve", "publication", "unlock")
          observedMode.readText().trim() shouldBe "700"
          val lockPath = java.nio.file.Path.of(observedLock.readText().trim())
          lockPath.toString().shouldStartWith(privateTmp.toString())
          lockPath.exists() shouldBe false
        }
      }

      test("a live lock rejects the contender before Docker and before current output reservation") {
        FakeHost().use { host ->
          val privateTmp = host.repositoryRoot.resolve("contended-tmp").also { it.createDirectories() }
          createLock(
            host = host,
            privateTmp = privateTmp,
            token = "contended",
            pid = ProcessHandle.current().pid(),
          )
          val result =
            host.invoke(
              *captureCommand(host, "contended").toTypedArray(),
              environment = mapOf("TMPDIR" to privateTmp.toString()),
            )

          result.exitCode shouldBe 2
          result.commands.filter { it.firstOrNull() == "docker" }.shouldBeEmpty()
          Files.list(host.artifactRoot).use { entries ->
            entries.noneMatch { it.fileName.toString().contains("contended") }
          } shouldBe true
        }
      }

      listOf(
          LockCase("PID reuse", ProcessHandle.current().pid(), "different-start", emptyMap()),
          LockCase("corrupt record", 999999, "", emptyMap(), record = "corrupt\n"),
          LockCase("dead but unprovable owner", 999999, TEST_START, mapOf("FAKE_PS_PID_STATE" to "unprovable")),
          LockCase(
            "dead owner with a matching Docker object",
            999999,
            TEST_START,
            mapOf("FAKE_PS_PID_STATE" to "dead", "FAKE_DOCKER_STALE_CONTAINERS" to "container-1\n"),
          ),
        )
        .forEach { case ->
          test("${case.name} cannot steal the operation lock") {
            FakeHost().use { host ->
              val privateTmp = host.repositoryRoot.resolve("unsafe-stale-tmp").also { it.createDirectories() }
              createLock(host, privateTmp, "unsafe-stale", case.pid, case.start, case.record)
              val result =
                host.invoke(
                  *captureCommand(host, "unsafe-stale").toTypedArray(),
                  environment = case.environment + ("TMPDIR" to privateTmp.toString()),
                )

              result.exitCode shouldBe 2
              val dockerCommands = result.commands.filter { it.firstOrNull() == "docker" }
              if (case.name == "dead owner with a matching Docker object") {
                dockerCommands.all { command ->
                  ("ps" in command && "-aq" in command) || ("volume" in command && "ls" in command)
                } shouldBe true
              } else {
                dockerCommands.shouldBeEmpty()
              }
            }
          }
        }

      test("a proven dead owner with no Docker objects permits one stale recovery") {
        FakeHost().use { host ->
          val privateTmp = host.repositoryRoot.resolve("safe-stale-tmp").also { it.createDirectories() }
          createLock(host, privateTmp, "safe-stale", 999999, TEST_START)
          val result =
            host.invoke(
              *captureCommand(host, "safe-stale").toTypedArray(),
              environment =
                mapOf(
                  "TMPDIR" to privateTmp.toString(),
                  "FAKE_PS_PID_STATE" to "dead",
                ),
            )

          result.exitCode shouldBe 0
          result.commands.count { "dev.revoman.performance.phase=recovery" in it } shouldBe 1
          lockPath(privateTmp).exists() shouldBe false
        }
      }
    },
  )

private const val TEST_START = "Mon Aug 18 00:00:00 2026"

private data class LockCase(
  val name: String,
  val pid: Long,
  val start: String,
  val environment: Map<String, String>,
  val record: String? = null,
)

private fun createLock(
  host: FakeHost,
  privateTmp: java.nio.file.Path,
  token: String,
  pid: Long,
  start: String = TEST_START,
  record: String? = null,
) {
  val lock = lockPath(privateTmp).also { it.createDirectories() }
  lock.resolve("owner").writeText(
    record
      ?: """pid=$pid
start=$start
operation=$token
profile=m4max-docker-linux-arm64-v1
adapter=${Sha256.digest(host.script).hex}
""",
  )
}

private fun lockPath(privateTmp: java.nio.file.Path) =
  privateTmp.resolve("revoman-performance-locks-v1/m4max-docker-linux-arm64-v1.lock")

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
