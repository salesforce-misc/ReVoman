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
import java.nio.file.Files
import performance.support.FakeHost

class ArtifactFailureContractTest :
  FunSpec(
    {
      test("cleanup failure after verification cannot publish valid evidence") {
        FakeHost().use { host ->
          val token = "cleanup-failure"
          val result =
            host.invoke(
              *captureCommand(host, token).toTypedArray(),
              functionOverrides = "adapter_cleanup_host_state() { return 1; }",
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "INPUT_OR_PREFLIGHT_INVALID"
          Files.exists(host.outputPath(token)) shouldBe false
          Files.exists(host.artifactRoot.resolve("INVALID-$token/INVALID/reason")) shouldBe true
        }
      }

      test("watcher failure after reservation is finalized only as INVALID") {
        FakeHost().use { host ->
          val token = "watcher-failure"
          val result =
            host.invoke(
              *captureCommand(host, token).toTypedArray(),
              environment = mapOf("FAKE_MEMORY_PRESSURE_FAIL_AFTER" to "12"),
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "QUALIFICATION_FAILED"
          Files.exists(host.outputPath(token)) shouldBe false
          Files.exists(host.artifactRoot.resolve("INVALID-$token/INVALID/reason")) shouldBe true
        }
      }

      test("pre-verification runtime failure writes no reservation staging or artifact") {
        FakeHost().use { host ->
          val token = "pre-verification-failure"
          val result =
            host.invoke(
              *captureCommand(host, token).toTypedArray(),
              environment = mapOf("FAKE_DOCKER_CONFIG_DIGEST" to "sha256:${"0".repeat(64)}"),
            )

          result.exitCode shouldBe 2
          listOf(
              host.outputPath(token),
              host.artifactRoot.resolve(".$token.reservation"),
              host.artifactRoot.resolve(".$token.staging"),
              host.artifactRoot.resolve("INVALID-$token"),
            )
            .forEach { path -> Files.exists(path) shouldBe false }
        }
      }

      test("failed preflight records cleanup failure in restoration evidence") {
        FakeHost().use { host ->
          val privateTmp = host.repositoryRoot.resolve("failed-restoration-tmp")
          Files.createDirectories(privateTmp)
          val result =
            host.invoke(
              *captureCommand(host, "failed-restoration").toTypedArray(),
              environment =
                mapOf(
                  "FAKE_PMSET_THERMAL_STATE" to "CPU_Speed_Limit=80",
                  "TMPDIR" to privateTmp.toString(),
                ),
              functionOverrides =
                """
                adapter_cleanup_host_state() { return 1; }
                adapter_cleanup_qualification_root() { return 0; }
                """.trimIndent(),
            )
          val qualificationRoot =
            Files.list(privateTmp).use { entries ->
              entries
                .filter { path -> path.fileName.toString().startsWith("revoman-qualification-") }
                .findFirst()
                .orElseThrow()
            }

          result.exitCode shouldBe 2
          Files.readString(qualificationRoot.resolve("restoration.json")) shouldContain
            "\"cleanupPassed\":false"
        }
      }
    },
  )

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
