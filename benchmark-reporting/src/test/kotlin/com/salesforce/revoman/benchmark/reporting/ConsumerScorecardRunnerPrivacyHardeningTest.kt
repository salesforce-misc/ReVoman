package com.salesforce.revoman.benchmark.reporting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ConsumerScorecardRunnerPrivacyHardeningTest :
  StringSpec({
    listOf("text", "binary").forEach { format ->
      "private identity in staged $format evidence is rejected before publication" {
        withRunnerFixture { fixture ->
          val privateIdentity = "private-identity-$format"
          val host =
            fixture.host.copy(
              privateMachineIdentity =
                PrivateMachineIdentity(
                  privateIdentity,
                  "/home/$privateIdentity",
                  "host-$privateIdentity",
                )
            )
          var publishMoveCalled = false
          val failure =
            shouldThrow<IllegalArgumentException> {
              ConsumerScorecardRunner(
                  host,
                  RecordingBenchmarkExecutor(),
                  reporter = { manifest ->
                    BenchmarkReportCli.run(
                        arrayOf("scorecard", "--manifest", manifest.toAbsolutePath().toString())
                      )
                      .also { status ->
                        if (status == 0) {
                          val stagedRun = manifest.parent
                          if (format == "text") {
                            Files.writeString(stagedRun.resolve("report.md"), privateIdentity)
                          } else {
                            Files.write(
                              stagedRun.resolve("raw/profiles/postmanV2TenStepRevUp/cpu.jfr"),
                              byteArrayOf(0, 1) + privateIdentity.toByteArray() + byteArrayOf(2, 3),
                            )
                          }
                        }
                      }
                  },
                  publishMove = { source, target ->
                    publishMoveCalled = true
                    Files.move(source, target)
                  },
                )
                .run(fixture.request)
            }

          failure.message shouldBe "Scorecard evidence contains a private identity"
          failure.message.orEmpty() shouldNotContain privateIdentity
          publishMoveCalled shouldBe false
          Files.exists(acceptedRun(fixture)) shouldBe false
          Files.readString(stagingRun(fixture).resolve("failure-summary.txt")).also { summary ->
            summary shouldContain "phase: privacy validation"
            summary shouldNotContain privateIdentity
          }
        }
      }
    }

    "sanitized fork properties cannot hide a private account-home JDK path" {
      withRunnerFixture { fixture ->
        val privateHome = Files.createTempDirectory("actual-account-home-").toAbsolutePath()
        try {
          val launcherHome = privateHome.resolve("jdk")
          val launcher = launcherHome.resolve("bin/java")
          Files.createDirectories(launcher.parent)
          Files.writeString(launcher, "executable")
          check(launcher.toFile().setExecutable(true))
          launcherHome.resolve("lib/libasyncProfiler.so").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "x")
          }
          launcherHome.resolve("bin/jfr").also {
            Files.writeString(it, "executable")
            check(it.toFile().setExecutable(true))
          }
          val identity =
            PrivateMachineIdentity(
              "actual-account",
              privateHome.toString(),
              "actual-kernel-host",
            )
          val host =
            fixture.host.copy(
              launcher = launcher,
              privateMachineIdentity = identity,
            )
          val executor = RecordingBenchmarkExecutor()

          val failure =
            shouldThrow<IllegalArgumentException> {
              ConsumerScorecardRunner(host, executor)
                .run(fixture.request.copy(javaExecutable = launcher))
            }

          failure.message shouldBe "Scorecard evidence contains a private identity"
          failure.message.orEmpty() shouldNotContain privateHome.toString()
          val forkArguments =
            executor.commands.filter { "-jvmArgsAppend" in it }.map(List<String>::last)
          forkArguments.forEach { arguments ->
            arguments shouldContain "-Duser.name=revoman-scorecard"
            arguments shouldContain "-Duser.home=/tmp/revoman-consumer-scorecard-"
          }
          forkArguments.any { arguments -> privateHome.toString() in arguments } shouldBe true
          Files.readString(stagingRun(fixture).resolve("failure-summary.txt")) shouldNotContain
            privateHome.toString()
          Files.exists(acceptedRun(fixture)) shouldBe false
        } finally {
          privateHome.toFile().deleteRecursively()
        }
      }
    }

    listOf("symlink", "special file").forEach { entryType ->
      "staged $entryType is rejected before publication" {
        withRunnerFixture { fixture ->
          var publishMoveCalled = false
          val failure =
            shouldThrow<IllegalArgumentException> {
              ConsumerScorecardRunner(
                  fixture.host,
                  RecordingBenchmarkExecutor(),
                  reporter = { manifest ->
                    BenchmarkReportCli.run(
                        arrayOf("scorecard", "--manifest", manifest.toAbsolutePath().toString())
                      )
                      .also { status ->
                        if (status == 0) {
                          val unexpected = manifest.parent.resolve("unexpected-entry")
                          if (entryType == "symlink") {
                            Files.createSymbolicLink(
                              unexpected,
                              manifest.parent.resolve("report.md"),
                            )
                          } else {
                            check(
                              ProcessBuilder("mkfifo", unexpected.toString()).start().waitFor() == 0
                            )
                          }
                        }
                      }
                  },
                  publishMove = { source, target ->
                    publishMoveCalled = true
                    Files.move(source, target)
                  },
                )
                .run(fixture.request)
            }

          failure.message shouldBe "Scorecard evidence contains an unsupported entry"
          failure.cause shouldBe null
          publishMoveCalled shouldBe false
          Files.exists(acceptedRun(fixture)) shouldBe false
        }
      }
    }

    listOf("added", "replaced").forEach { mutation ->
      "$mutation evidence after the initial scan is rejected before publication" {
        withRunnerFixture { fixture ->
          var publishMoveCalled = false
          val failure =
            shouldThrow<IllegalArgumentException> {
              ConsumerScorecardRunner(
                  fixture.host,
                  RecordingBenchmarkExecutor(),
                  runtimeCleanup = { runtimeRoot ->
                    deleteScorecardRuntimeWorkspace(runtimeRoot)
                    val stagedRun = stagingRun(fixture)
                    if (mutation == "added") {
                      Files.writeString(stagedRun.resolve("added-after-scan.txt"), "added")
                    } else {
                      val replacement = stagedRun.resolve("replacement.tmp")
                      Files.writeString(replacement, "replacement")
                      Files.move(replacement, stagedRun.resolve("report.md"), REPLACE_EXISTING)
                    }
                  },
                  publishMove = { source, target ->
                    publishMoveCalled = true
                    Files.move(source, target)
                  },
                )
                .run(fixture.request)
            }

          failure.message shouldBe "Scorecard evidence changed after privacy validation"
          failure.cause shouldBe null
          publishMoveCalled shouldBe false
          Files.exists(acceptedRun(fixture)) shouldBe false
        }
      }
    }

    "runtime artifact symlinks are rejected instead of copied into staging" {
      withRunnerFixture { fixture ->
        val delegate = RecordingBenchmarkExecutor()
        var injected = false
        val executor = ProcessExecutor { command, workingDirectory ->
          if (!injected && command.any { "-agentpath:" in it }) {
            injected = true
            val recording =
              Path.of(command.last().substringAfter("file=").substringBefore(",loglevel=warn"))
            Files.createDirectories(recording.parent)
            val target = recording.resolveSibling("actual.jfr")
            Files.write(target, byteArrayOf(7, 8, 9))
            Files.createSymbolicLink(recording, target)
            ProcessResult(0, "profile complete\n", "")
          } else {
            delegate.execute(command, workingDirectory)
          }
        }

        val failure =
          shouldThrow<IllegalArgumentException> {
            ConsumerScorecardRunner(fixture.host, executor).run(fixture.request)
          }

        failure.message shouldBe "Runtime artifact must be a regular file"
        Files.exists(acceptedRun(fixture)) shouldBe false
        Files.exists(
          stagingRun(fixture).resolve("raw/profiles/postmanV2TenStepRevUp/cpu.jfr")
        ) shouldBe false
      }
    }

    listOf(
        "profile" to "raw/profiles/postmanV2TenStepRevUp/cpu.jfr",
        "final" to "raw/results.csv",
      )
      .forEach { (child, relativeArtifact) ->
        "nonzero $child child preserves its regular artifact only in the rejected attempt" {
          withRunnerFixture { fixture ->
            val artifactBytes = byteArrayOf(0, 3, 1, 4, 1, 5, 9, 0)
            val delegate = RecordingBenchmarkExecutor()
            val executor = ProcessExecutor { command, workingDirectory ->
              val isFailedChild =
                if (child == "profile") {
                  command.any { "-agentpath:" in it }
                } else {
                  command.contains("-rff")
                }
              if (isFailedChild) {
                val runtimeArtifact =
                  if (child == "profile") {
                    Path.of(
                      command.last().substringAfter("file=").substringBefore(",loglevel=warn")
                    )
                  } else {
                    Path.of(command[command.indexOf("-rff") + 1])
                  }
                Files.createDirectories(runtimeArtifact.parent)
                Files.write(runtimeArtifact, artifactBytes)
                ProcessResult(23, "", "injected $child failure")
              } else {
                delegate.execute(command, workingDirectory)
              }
            }

            shouldThrow<IllegalArgumentException> {
                ConsumerScorecardRunner(fixture.host, executor).run(fixture.request)
              }
              .message shouldContain "injected $child failure"

            Files.readAllBytes(stagingRun(fixture).resolve(relativeArtifact)) shouldBe artifactBytes
            validateEvidencePrivacy(stagingRun(fixture), fixture.host.privateMachineIdentity)
            Files.exists(acceptedRun(fixture)) shouldBe false
          }
        }
      }

    "privacy scan filesystem failures do not disclose paths or causes" {
      withRunnerFixture { fixture ->
        val missing = fixture.request.projectRoot.resolve("private-missing-path")

        val failure =
          shouldThrow<IllegalArgumentException> {
            validateEvidencePrivacy(missing, fixture.host.privateMachineIdentity)
          }

        failure.message shouldBe "Scorecard evidence privacy validation failed"
        failure.message.orEmpty() shouldNotContain missing.toString()
        failure.cause shouldBe null
      }
    }

    "private identity resolution failure occurs before any profile child starts" {
      withRunnerFixture { fixture ->
        val host = fixture.host.copy(privateMachineIdentity = PrivateMachineIdentity("", "", ""))
        val executor = RecordingBenchmarkExecutor()

        val failure =
          shouldThrow<IllegalArgumentException> {
            ConsumerScorecardRunner(host, executor).run(fixture.request)
          }

        failure.message shouldBe "Private machine identity is unavailable"
        executor.commands shouldBe emptyList()
        Files.exists(acceptedRun(fixture)) shouldBe false
      }
    }
  })
