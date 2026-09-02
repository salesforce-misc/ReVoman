package com.salesforce.revoman.benchmark.reporting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ConsumerScorecardRunnerHardeningTest :
  StringSpec({
    "preflight requires observed Gradle daemon identity metadata" {
      withRunnerFixture { fixture ->
        listOf(
            fixture.request.copy(gradleDaemonRuntimeVersion = ""),
            fixture.request.copy(gradleDaemonVendor = ""),
            fixture.request.copy(gradleDaemonVmName = ""),
          )
          .forEach { request ->
            shouldThrow<IllegalArgumentException> {
                ConsumerScorecardRunner(fixture.host, fixture.executor).preflight(request)
              }
              .message shouldContain "Gradle daemon identity"
          }
      }
    }

    "preflight requires an executable JMH manifest and the complete benchmark list" {
      withRunnerFixture { fixture ->
        listOf<(Path) -> Unit>(
            { path -> writeJmhJar(path, mainClass = null) },
            { path -> writeJmhJar(path, mainClass = "example.WrongMain") },
            { path -> writeJmhJar(path, benchmarks = emptyList()) },
            { path -> writeJmhJar(path, benchmarks = SCORECARD_BENCHMARKS.dropLast(1)) },
            { path ->
              writeJmhJar(
                path,
                benchmarks =
                  SCORECARD_BENCHMARKS.dropLast(1) + "${SCORECARD_BENCHMARKS.last()}Extra",
              )
            },
          )
          .forEach { invalidJar ->
            invalidJar(fixture.request.benchmarkJar)

            shouldThrow<IllegalArgumentException> {
                ConsumerScorecardRunner(fixture.host, fixture.executor).preflight(fixture.request)
              }
              .message shouldContain "JMH"
          }
      }
    }

    "runner retains a diagnostic attempt for Java preflight failure" {
      withRunnerFixture { fixture ->
        shouldThrow<IllegalArgumentException> {
          ConsumerScorecardRunner(fixture.host, fixture.executor)
            .run(fixture.request.copy(javaFeature = 21))
        }

        Files.readString(stagingRun(fixture).resolve("failure-summary.txt")) shouldContain
          "phase: preflight"
      }
    }

    "runner retains a diagnostic attempt for revision preflight failure" {
      withRunnerFixture { fixture ->
        fixture.request.runtimeValidation.writeText(
          validRuntimeValidation("ffffffffffffffffffffffffffffffffffffffff")
        )

        shouldThrow<IllegalArgumentException> {
          ConsumerScorecardRunner(fixture.host, fixture.executor).run(fixture.request)
        }

        Files.readString(stagingRun(fixture).resolve("failure-summary.txt")) shouldContain
          "phase: preflight"
      }
    }

    "runner retains a diagnostic attempt for dirty-worktree preflight failure" {
      withRunnerFixture { fixture ->
        val host = fixture.host.copy(gitStatus = " M unapproved.txt\u0000")

        shouldThrow<IllegalArgumentException> {
          ConsumerScorecardRunner(host, fixture.executor).run(fixture.request)
        }

        Files.readString(stagingRun(fixture).resolve("failure-summary.txt")) shouldContain
          "phase: preflight"
      }
    }

    "runner retains a diagnostic attempt for runtime-validation preflight failure" {
      withRunnerFixture { fixture ->
        fixture.request.runtimeValidation.writeText(
          validRuntimeValidation(REVISION).replace("\"runbookContracts\": true,", "")
        )

        shouldThrow<IllegalArgumentException> {
          ConsumerScorecardRunner(fixture.host, fixture.executor).run(fixture.request)
        }

        Files.readString(stagingRun(fixture).resolve("failure-summary.txt")) shouldContain
          "phase: preflight"
      }
    }

    "timestamp staging collision preserves the prior attempt and allocates a diagnostic sibling" {
      withRunnerFixture { fixture ->
        val prior = stagingRun(fixture)
        Files.createDirectories(prior)
        Files.writeString(prior.resolve("owned.txt"), "keep")

        shouldThrow<IllegalArgumentException> {
          ConsumerScorecardRunner(fixture.host, fixture.executor)
            .run(fixture.request.copy(javaFeature = 21))
        }

        Files.readString(prior.resolve("owned.txt")) shouldBe "keep"
        val diagnostic = prior.resolveSibling("${prior.fileName}-diagnostic-1")
        Files.readString(diagnostic.resolve("failure-summary.txt")) shouldContain
          "runId: 20260902T010203Z"
      }
    }

    "reserved staging and publication symlinks are rejected without redirected writes" {
      val unsafeLinks =
        listOf<Pair<String, (RunnerFixture, Path) -> Path>>(
          "staging root" to
            { fixture, redirect ->
              Files.createSymbolicLink(
                fixture.request.projectRoot.resolve(".benchmark-staging"),
                redirect,
              )
            },
          "staging study" to
            { fixture, redirect ->
              val root = fixture.request.projectRoot.resolve(".benchmark-staging")
              Files.createDirectories(root)
              Files.createSymbolicLink(root.resolve(SCORECARD_STUDY_ID), redirect)
            },
          "publication root" to
            { fixture, redirect ->
              Files.createSymbolicLink(
                fixture.request.projectRoot.resolve("benchmark-results"),
                redirect,
              )
            },
          "publication study" to
            { fixture, redirect ->
              val root = fixture.request.projectRoot.resolve("benchmark-results")
              Files.createDirectories(root)
              Files.createSymbolicLink(root.resolve(SCORECARD_STUDY_ID), redirect)
            },
          "publication run" to
            { fixture, redirect ->
              val root =
                fixture.request.projectRoot.resolve("benchmark-results/$SCORECARD_STUDY_ID")
              Files.createDirectories(root)
              Files.createSymbolicLink(root.resolve("20260902T010203Z"), redirect)
            },
        )

      unsafeLinks.forEach { (case, createUnsafeLink) ->
        withRunnerFixture { fixture ->
          val redirect = fixture.request.projectRoot.resolve("redirect-$case")
          Files.createDirectory(redirect)
          createUnsafeLink(fixture, redirect)

          shouldThrow<IllegalArgumentException> {
              ConsumerScorecardRunner(fixture.host, fixture.executor).run(fixture.request)
            }
            .message shouldContain "symbolic link"

          Files.list(redirect).use { it.count() } shouldBe 0
          Files.exists(
            fixture.request.projectRoot.resolve(
              ".benchmark-staging/$SCORECARD_STUDY_ID/20260902T010203Z/failure-summary.txt"
            )
          ) shouldBe false
        }
      }
    }

    "runner canonicalizes a symbolic project-root alias before reserving evidence paths" {
      withRunnerFixture { fixture ->
        val alias =
          fixture.request.projectRoot.resolveSibling(
            "${fixture.request.projectRoot.fileName}-alias"
          )
        Files.createSymbolicLink(alias, fixture.request.projectRoot)
        try {
          val accepted =
            ConsumerScorecardRunner(fixture.host, RecordingBenchmarkExecutor())
              .run(fixture.request.copy(projectRoot = alias))

          accepted.startsWith(fixture.request.projectRoot.toRealPath()) shouldBe true
        } finally {
          Files.deleteIfExists(alias)
        }
      }
    }

    "preflight exposes the canonical project root for a symbolic alias" {
      withRunnerFixture { fixture ->
        val alias =
          fixture.request.projectRoot.resolveSibling(
            "${fixture.request.projectRoot.fileName}-alias"
          )
        Files.createSymbolicLink(alias, fixture.request.projectRoot)
        try {
          val relativeJar = fixture.request.projectRoot.relativize(fixture.request.benchmarkJar)
          val relativeValidation =
            fixture.request.projectRoot.relativize(fixture.request.runtimeValidation)
          val preflight =
            ConsumerScorecardRunner(fixture.host, fixture.executor)
              .preflight(
                fixture.request.copy(
                  projectRoot = alias,
                  benchmarkJar = alias.resolve(relativeJar),
                  runtimeValidation = alias.resolve(relativeValidation),
                )
              )

          preflight.projectRoot shouldBe fixture.request.projectRoot.toRealPath()
        } finally {
          Files.deleteIfExists(alias)
        }
      }
    }

    "unsafe publication components fail before any staging path is written" {
      withRunnerFixture { fixture ->
        Files.writeString(
          fixture.request.projectRoot.resolve("benchmark-results"),
          "not a directory",
        )

        shouldThrow<IllegalArgumentException> {
          ConsumerScorecardRunner(fixture.host, fixture.executor).run(fixture.request)
        }

        Files.exists(fixture.request.projectRoot.resolve(".benchmark-staging")) shouldBe false
      }
    }

    "cross-filesystem publication never uses the non-atomic fallback" {
      withRunnerFixture { fixture ->
        var nonAtomicMoveCalled = false
        val moves =
          object : ScorecardMoveOperations {
            override fun atomicMove(source: Path, target: Path) {
              throw AtomicMoveNotSupportedException(
                source.toString(),
                target.toString(),
                "injected",
              )
            }

            override fun sameFileStore(source: Path, targetParent: Path): Boolean = false

            override fun nonAtomicMove(source: Path, target: Path) {
              nonAtomicMoveCalled = true
            }
          }

        shouldThrow<IllegalArgumentException> {
            ConsumerScorecardRunner(
                fixture.host,
                RecordingBenchmarkExecutor(),
                publishMove = { source, target -> moveCompleteRun(source, target, moves) },
              )
              .run(fixture.request)
          }
          .message shouldContain "same filesystem"

        nonAtomicMoveCalled shouldBe false
        Files.exists(acceptedRun(fixture)) shouldBe false
        Files.isRegularFile(stagingRun(fixture).resolve("failure-summary.txt")) shouldBe true
      }
    }
  })
