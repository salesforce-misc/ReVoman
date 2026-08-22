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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import performance.support.FakeHost

class ArtifactRootContractTest :
  FunSpec(
    {
      test("fake Docker bounds nested finalizer watchdogs without changing production policy") {
        FakeHost().use { host ->
          val result =
            host.invokeFunction(
              "adapter_docker_timeout_millis",
              "run",
              "--label",
              "dev.revoman.performance.phase=finalizer",
              environment = mapOf("FAKE_DOCKER_TIMEOUT_MILLIS" to "5"),
              functionOverrides =
                "adapter_docker_timeout_millis() { printf '%s' \"${'$'}FAKE_DOCKER_TIMEOUT_MILLIS\"; }",
            )

          result.exitCode shouldBe 0
          result.standardOutput shouldBe "5"
        }
      }

      test("absolute output is rejected without exposing the path or promising an artifact") {
        FakeHost().use { host ->
          val sensitivePath = host.repositoryRoot.resolve("private/secret-output")
          val result = host.invokeArtifact(*initialFreeze(host, sensitivePath.toString()).toTypedArray())

          result.exitCode shouldBe 2
          result.standardError shouldContain "OUTPUT_ABSOLUTE"
          result.standardError shouldNotContain sensitivePath.toString()
          result.commands.shouldBeEmpty()
          Files.exists(sensitivePath) shouldBe false
        }
      }

      mapOf(
          "parent traversal" to "build/performance/../escape",
          "disposable artifact root" to "build/performance",
          "reviewed artifact root" to "docs/superpowers/benchmarks",
        )
        .forEach { (condition, output) ->
          test("$condition is rejected before invoking the host") {
            FakeHost().use { host ->
              val result = host.invokeArtifact(*initialFreeze(host, output).toTypedArray())

              result.exitCode shouldBe 2
              result.standardError shouldContain "OUTPUT_INVALID"
              result.commands.shouldBeEmpty()
            }
          }
        }

      listOf(
          "build/performance/password",
          "build/performance/192.168.1.1",
          "build/performance/ghp_0123456789abcdefghijkl",
        )
        .forEach { output ->
          test("a privacy-unsafe logical output token is rejected") {
            FakeHost().use { host ->
              val result = host.invokeArtifact(*initialFreeze(host, output).toTypedArray())

              result.exitCode shouldBe 2
              result.standardError shouldContain "OUTPUT_INVALID"
              result.standardError shouldNotContain output.substringAfterLast('/')
              result.commands.shouldBeEmpty()
            }
          }
        }

      test("symlink traversal is rejected before reserving output") {
        FakeHost().use { host ->
          val outside = host.repositoryRoot.resolve("outside").also(Files::createDirectories)
          Files.createSymbolicLink(host.artifactRoot.resolve("link"), outside)

          val result =
            host.invokeArtifact(*initialFreeze(host, "build/performance/link/escape").toTypedArray())

          result.exitCode shouldBe 2
          result.standardError shouldContain "OUTPUT_SYMLINK"
          result.commands.shouldBeEmpty()
          Files.exists(outside.resolve("escape")) shouldBe false
        }
      }

      test("an existing output is never overwritten") {
        FakeHost().use { host ->
          val output = host.outputPath("existing").also(Files::createDirectory)
          output.resolve("owned.txt").toFile().writeText("keep")

          val result = host.invokeArtifact(*initialFreeze(host, host.output("existing")).toTypedArray())

          result.exitCode shouldBe 2
          result.standardError shouldContain "OUTPUT_EXISTS"
          output.resolve("owned.txt").toFile().readText() shouldBe "keep"
          result.commands.shouldBeEmpty()
        }
      }

      test("an initially unwritable parent fails as input without an artifact promise") {
        FakeHost().use { host ->
          val parent = host.artifactRoot.resolve("locked").also(Files::createDirectory)
          val originalPermissions = Files.getPosixFilePermissions(parent)
          Files.setPosixFilePermissions(
            parent,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
          )
          try {
            val output = "build/performance/locked/unwritable"
            val result = host.invokeArtifact(*initialFreeze(host, output).toTypedArray())

            result.exitCode shouldBe 2
            result.standardError shouldContain "OUTPUT_UNWRITABLE"
            result.standardError shouldNotContain host.repositoryRoot.toString()
            result.commands.shouldBeEmpty()
            Files.exists(host.repositoryRoot.resolve(output)) shouldBe false
          } finally {
            Files.setPosixFilePermissions(parent, originalPermissions)
          }
        }
      }

      mapOf(
          "write" to "adapter_write_reservation() { return 1; }",
          "fsync" to "adapter_fsync_path() { return 1; }",
        )
        .forEach { (operation, override) ->
          test("an injected $operation failure after reservation exits eight") {
            FakeHost().use { host ->
              val token = "publication-$operation"
              val result =
                host.invokeArtifact(
                  *initialFreeze(host, host.output(token)).toTypedArray(),
                  functionOverrides = override,
                )

              result.exitCode shouldBe 8
              result.standardError shouldContain "PUBLICATION_FAILED"
              result.standardError shouldNotContain host.repositoryRoot.toString()
              Files.exists(host.outputPath(token)) shouldBe false
            }
          }
        }

      test("an injected atomic move failure after reservation exits eight") {
        FakeHost().use { host ->
          val token = "publication-move"
          val result =
            host.invokeArtifact(
              *initialFreeze(host, host.output(token)).toTypedArray(),
              environment = mapOf("FAKE_DOCKER_FINALIZER_BOUNDARY" to "move-failure"),
            )

          result.exitCode shouldBe 8
          result.standardError shouldContain "PUBLICATION_FAILED"
          result.standardError shouldNotContain host.repositoryRoot.toString()
          Files.exists(host.outputPath(token)) shouldBe false
          Files.exists(host.artifactRoot.resolve(".$token.staging/metadata/distribution.sha256")) shouldBe
            true
        }
      }

      test("a partial finalizer failure is not recovered or published by the host") {
        FakeHost().use { host ->
          val token = "partial-finalizer"
          val result =
            host.invokeArtifact(
              *initialFreeze(host, host.output(token)).toTypedArray(),
              functionOverrides =
                "adapter_run_finalizer() { /bin/mkdir -p \"\$ADAPTER_STAGING\"; return 1; }",
            )

          result.exitCode shouldBe 8
          result.standardError shouldContain "PUBLICATION_FAILED"
          Files.exists(host.outputPath(token)) shouldBe false
          Files.isDirectory(host.artifactRoot.resolve(".$token.staging")) shouldBe true
        }
      }

      test("a context failure before finalizer verification makes no artifact promise") {
        FakeHost().use { host ->
          val token = "context-unavailable"
          val result =
            host.invokeArtifact(
              *initialFreeze(host, host.output(token)).toTypedArray(),
              functionOverrides = "adapter_select_substrate() { return 1; }",
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "CONTEXT_INVALID"
          Files.exists(host.outputPath(token)) shouldBe false
          Files.exists(host.artifactRoot.resolve(".$token.reservation")) shouldBe false
        }
      }

      test("a frozen finalizer verification failure occurs before output reservation") {
        FakeHost().use { host ->
          val token = "finalizer-unavailable"
          val result =
            host.invokeArtifact(
              *initialFreeze(host, host.output(token)).toTypedArray(),
              environment =
                mapOf(
                  "FAKE_DOCKER_FAIL_MATCH" to
                    "dev.revoman.performance.phase=finalizer-verification",
                ),
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "FINALIZER_UNAVAILABLE"
          Files.exists(host.outputPath(token)) shouldBe false
          Files.exists(host.artifactRoot.resolve(".$token.reservation")) shouldBe false
          Files.exists(host.artifactRoot.resolve(".$token.staging")) shouldBe false
        }
      }

      test("output reservation starts only after the frozen finalizer verification phase") {
        FakeHost().use { host ->
          val token = "verified-before-reserve"
          val result =
            host.invokeArtifact(
              *initialFreeze(host, host.output(token)).toTypedArray(),
              functionOverrides =
                """
                adapter_write_reservation() {
                  test -f "${'$'}ADAPTER_REPO_ROOT/.fake-finalizer-verified" || return 1
                  (umask 077 && printf '%s\n' "${'$'}2" >"${'$'}1")
                }
                """.trimIndent(),
            )

          result.exitCode shouldBe 0
          result.standardError shouldBe ""
          Files.exists(host.outputPath(token).resolve("metadata/distribution.sha256")) shouldBe true
        }
      }

      test("the verified runner owns freeze validation and exact GNU publication") {
        FakeHost().use { host ->
          val result = host.invokeArtifact(*initialFreeze(host, host.output("gnu-publication")).toTypedArray())
          val finalizer =
            result.commands.single { command ->
              "dev.revoman.performance.phase=finalizer" in command
            }
          val child = finalizer.last()

          child shouldContain "runner=/inputs/finalizer/bin/performance-runner"
          child shouldContain "test -x \"\$runner\""
          child shouldContain "test ! -L \"\$runner\""
          child shouldContain "\"\$runner\" \"\$REVOMAN_FINALIZER_COMMAND\""
          child shouldContain "finalizer_source=/operation/provisional/distribution"
          child shouldContain "--source \"\$finalizer_source\""
          child shouldNotContain "/usr/bin/tar"
          child shouldNotContain "/usr/bin/mv -nT"
          child shouldNotContain "/usr/bin/rm -f \"\$token_file\""
          child shouldNotContain "INVALID/reason"
          child shouldNotContain "adapter-failure"
          child shouldNotContain "nested_source"
          finalizer.any { argument -> argument.startsWith("type=volume,src=") } shouldBe true
          finalizer.any { argument -> argument.endsWith("dst=/inputs,readonly") } shouldBe true
          finalizer.contains("REVOMAN_FINALIZER_COMMAND=finalize-freeze") shouldBe true
        }
      }

      test("late file directory and symlink targets never overwrite nest or escape staging") {
        listOf("late-file", "late-directory", "late-symlink").forEach { boundary ->
          FakeHost().use { host ->
            val token = "publication-$boundary"
            val escape = host.artifactRoot.resolve("$token-escape").also(Files::createDirectory)
            Files.writeString(escape.resolve("foreign.txt"), "keep")
            val result =
              host.invokeArtifact(
                *initialFreeze(host, host.output(token)).toTypedArray(),
                environment = mapOf("FAKE_DOCKER_FINALIZER_BOUNDARY" to boundary),
              )
            val target = host.outputPath(token)
            val staging = host.artifactRoot.resolve(".$token.staging")

            result.exitCode shouldBe 8
            result.standardError shouldContain "PUBLICATION_FAILED"
            Files.exists(staging.resolve("metadata/distribution.sha256")) shouldBe true
            Files.exists(target.resolve(".$token.staging")) shouldBe false
            Files.readString(escape.resolve("foreign.txt")) shouldBe "keep"
            Files.exists(escape.resolve("metadata/distribution.sha256")) shouldBe false
            if (boundary == "late-file") {
              Files.isRegularFile(target) shouldBe true
              Files.readString(target) shouldBe "keep"
            } else if (boundary == "late-directory") {
              Files.isDirectory(target) shouldBe true
              Files.readString(target.resolve("foreign.txt")) shouldBe "keep"
              Files.exists(target.resolve("metadata/distribution.sha256")) shouldBe false
            } else {
              Files.isSymbolicLink(target) shouldBe true
              target.toRealPath() shouldBe escape.toRealPath()
            }
          }
        }
      }

      test("a failure at the pre-move boundary leaves staging inside its owned parent") {
        FakeHost().use { host ->
          val token = "pre-move-boundary"
          val result =
            host.invokeArtifact(
              *initialFreeze(host, host.output(token)).toTypedArray(),
              environment = mapOf("FAKE_DOCKER_FINALIZER_BOUNDARY" to "pre-move-failure"),
            )

          result.exitCode shouldBe 8
          result.standardError shouldContain "PUBLICATION_FAILED"
          Files.exists(host.outputPath(token)) shouldBe false
          Files.exists(
            host.artifactRoot.resolve(".$token.staging/metadata/distribution.sha256"),
          ) shouldBe true
          Files.exists(host.artifactRoot.parent.resolve(".$token.staging")) shouldBe false
        }
      }

      test("a substituted finalizer bind is rejected before any write") {
        FakeHost().use { host ->
          val token = "substituted-bind"
          val substituted = host.repositoryRoot.resolve("substituted-bind").also(Files::createDirectory)
          Files.writeString(substituted.resolve("foreign.txt"), "keep")
          val result =
            host.invokeArtifact(
              *initialFreeze(host, host.output(token)).toTypedArray(),
              environment = mapOf("FAKE_DOCKER_FINALIZER_BIND_SOURCE" to substituted.toString()),
            )

          result.exitCode shouldBe 8
          result.standardError shouldContain "PUBLICATION_FAILED"
          Files.exists(host.outputPath(token)) shouldBe false
          Files.exists(host.artifactRoot.resolve(".$token.staging")) shouldBe false
          Files.readString(substituted.resolve("foreign.txt")) shouldBe "keep"
          Files.list(substituted).use { paths -> paths.count() shouldBe 1 }
        }
      }

      mapOf(
          "adapter hash" to "adapter_sha256_file() { return 1; }",
          "UTC clock" to "adapter_observed_at_utc() { return 1; }",
        )
        .forEach { (dependency, override) ->
          test("an unavailable $dependency fails before reservation with exit two") {
            FakeHost().use { host ->
              val token = "unavailable-${dependency.replace(' ', '-')}"
              val result =
                host.invokeArtifact(
                  *initialFreeze(host, host.output(token)).toTypedArray(),
                  functionOverrides = override,
                )

              result.exitCode shouldBe 2
              result.standardError shouldContain "INTERNAL_ERROR"
              Files.exists(host.outputPath(token)) shouldBe false
              Files.exists(host.artifactRoot.resolve(".$token.reservation")) shouldBe false
            }
          }
        }
    },
  )

private fun initialFreeze(host: FakeHost, output: String): List<String> =
  listOf(
    "freeze",
    "--treatment-source",
    host.treatmentSource().toString(),
    "--output",
    output,
  )

private fun FakeHost.invokeArtifact(
  vararg arguments: String,
  environment: Map<String, String> = emptyMap(),
  functionOverrides: String? = null,
): performance.support.AdapterInvocation =
  invoke(
    *arguments,
    environment = environment,
    functionOverrides =
      listOf(
        "adapter_docker_timeout_millis() { printf '%s' \"${'$'}{FAKE_DOCKER_TIMEOUT_MILLIS:-10000}\"; }",
        functionOverrides,
      ).filterNotNull().joinToString(separator = "\n"),
  )
