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
      test("absolute output is rejected without exposing the path or promising an artifact") {
        FakeHost().use { host ->
          val sensitivePath = host.repositoryRoot.resolve("private/secret-output")
          val result = host.invoke(*initialFreeze(host, sensitivePath.toString()).toTypedArray())

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
              val result = host.invoke(*initialFreeze(host, output).toTypedArray())

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
              val result = host.invoke(*initialFreeze(host, output).toTypedArray())

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
            host.invoke(*initialFreeze(host, "build/performance/link/escape").toTypedArray())

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

          val result = host.invoke(*initialFreeze(host, host.output("existing")).toTypedArray())

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
            val result = host.invoke(*initialFreeze(host, output).toTypedArray())

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
          "rename" to "adapter_atomic_rename() { return 1; }",
        )
        .forEach { (operation, override) ->
          test("an injected $operation failure after reservation exits eight") {
            FakeHost().use { host ->
              val token = "publication-$operation"
              val result =
                host.invoke(
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

      test("a partial finalizer stage is replaced by a complete host failure envelope") {
        FakeHost().use { host ->
          val token = "partial-finalizer"
          val result =
            host.invoke(
              *initialFreeze(host, host.output(token)).toTypedArray(),
              functionOverrides =
                "adapter_run_finalizer() { /bin/mkdir -p \"\$ADAPTER_STAGING\"; return 1; }",
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "INTERNAL_ERROR"
          Files.readString(host.outputPath(token).resolve("adapter-failure.json")) shouldContain
            "\"failureCode\":\"INTERNAL_ERROR\""
        }
      }

      test("a context failure after reservation publishes a sanitized failure envelope") {
        FakeHost().use { host ->
          val token = "context-unavailable"
          val result =
            host.invoke(
              *initialFreeze(host, host.output(token)).toTypedArray(),
              functionOverrides = "adapter_select_substrate() { return 1; }",
            )

          result.exitCode shouldBe 2
          result.standardError shouldContain "CONTEXT_INVALID"
          Files.readString(host.outputPath(token).resolve("adapter-failure.json")) shouldContain
            "\"failureCode\":\"CONTEXT_INVALID\""
          Files.exists(host.artifactRoot.resolve(".$token.reservation")) shouldBe false
        }
      }

      test("publication rejects parent substitution and late directory targets without nesting") {
        FakeHost().use { host ->
          val token = "parent-substitution"
          val result =
            host.invoke(
              *initialFreeze(host, host.output(token)).toTypedArray(),
              functionOverrides =
                """
                adapter_fsync_path() {
                  parent="${'$'}{ADAPTER_OUTPUT_PARENT_PATH:-${'$'}ADAPTER_OUTPUT_PARENT}"
                  displaced="${'$'}parent.swapped"
                  if [ "${'$'}1" = "${'$'}ADAPTER_STAGING" ] && [ ! -e "${'$'}displaced" ]; then
                    /bin/mv "${'$'}parent" "${'$'}displaced" || return 1
                    /bin/mkdir "${'$'}parent" || return 1
                    /bin/cp -R "${'$'}displaced/.${'$'}ADAPTER_RUN_TOKEN.reservation" "${'$'}parent/.${'$'}ADAPTER_RUN_TOKEN.reservation" || return 1
                    /bin/cp -R "${'$'}displaced/.${'$'}ADAPTER_RUN_TOKEN.staging" "${'$'}parent/.${'$'}ADAPTER_RUN_TOKEN.staging" || return 1
                  fi
                  /bin/sync
                }
                """.trimIndent(),
            )

          result.exitCode shouldBe 8
          result.standardError shouldContain "PUBLICATION_FAILED"
          Files.exists(host.outputPath(token)) shouldBe false
          Files.exists(
            host.repositoryRoot
              .resolve("build/performance.swapped/.$token.staging/adapter-failure.json"),
          ) shouldBe true
        }

        FakeHost().use { host ->
          val token = "late-directory-target"
          val result =
            host.invoke(
              *initialFreeze(host, host.output(token)).toTypedArray(),
              functionOverrides =
                """
                adapter_path_identity() {
                  if [ -n "${'$'}{ADAPTER_STAGING:-}" ] && [ "${'$'}1" = "${'$'}ADAPTER_STAGING" ]; then
                    sentinel="${'$'}ADAPTER_RESERVATION/.staging-identity-seen"
                    if [ -e "${'$'}sentinel" ]; then
                      /bin/mkdir "${'$'}ADAPTER_OUTPUT_TARGET" || return 1
                    else
                      : > "${'$'}sentinel" || return 1
                    fi
                  fi
                  case "${'$'}(/usr/bin/uname -s)" in
                    Darwin) /usr/bin/stat -f '%d:%i' "${'$'}1" ;;
                    Linux) /usr/bin/stat -c '%d:%i' "${'$'}1" ;;
                    *) return 1 ;;
                  esac
                }
                """.trimIndent(),
            )
          val lateTarget = host.outputPath(token)

          result.exitCode shouldBe 8
          result.standardError shouldContain "PUBLICATION_FAILED"
          Files.isDirectory(lateTarget) shouldBe true
          Files.exists(lateTarget.resolve(".$token.staging")) shouldBe false
          Files.exists(lateTarget.resolve("adapter-failure.json")) shouldBe false
          Files.exists(host.artifactRoot.resolve(".$token.staging/adapter-failure.json")) shouldBe true
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
                host.invoke(
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
