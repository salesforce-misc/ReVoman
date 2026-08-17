/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import performance.json.CanonicalJson
import performance.support.PerformanceTestProject
import performance.support.readJarEntries
import performance.support.readUtf8
import performance.support.sha256
import tools.jackson.databind.node.ObjectNode

class DistributionFreezeContractTest :
  FunSpec(
    {
      test("initial freeze keeps classpaths byte exact ordered and independently proven") {
        PerformanceTestProject.create().use { project ->
          val treatment = project.treatment("initial")
          val distribution = project.root.resolve("build/performance/initial")

          project.build(
            "assemblePerformanceDistribution",
            "verifyPerformanceDistribution",
            "-PperformanceTreatmentSource=${treatment.root}",
            "-PperformanceTreatmentJar=${treatment.jar}",
            "-PperformanceDistributionDirectory=$distribution",
          )

          distributionFiles(distribution) shouldContainExactlyInAnyOrder expectedDistributionFiles()
          Files.readAllBytes(distribution.resolve("app/revoman.jar")) shouldBe
            Files.readAllBytes(treatment.jar)
          Files.readAllBytes(distribution.resolve("lib/runtime-dependency.jar")) shouldBe
            Files.readAllBytes(project.inputs.resolve("runtime-dependency.jar"))
          Files.readAllBytes(distribution.resolve("runner/performance-runner.jar")) shouldBe
            Files.readAllBytes(
              project.inputs.resolve("runner-dist/lib/performance-runner.jar"),
            )
          Files.readAllBytes(distribution.resolve("runner/lib/runner-dependency.jar")) shouldBe
            Files.readAllBytes(project.inputs.resolve("runner-dist/lib/runner-dependency.jar"))
          Files.exists(distribution.resolve("lib/embedded-dependency.jar")) shouldBe false
          readJarEntries(distribution.resolve("app/revoman.jar")) shouldContainExactlyInAnyOrder
            setOf(
              "META-INF/MANIFEST.MF",
              "fixture/embedded/Embedded.class",
              "treatment-version.txt",
            )

          val classpath = readUtf8(distribution.resolve("metadata/classpath.json"))
          val benchmarkClasspath = orderedPaths(classpath, "benchmarkClasspath")
          benchmarkClasspath.take(2) shouldContainExactly
            listOf(
              "benchmark/revoman-jmh.jar",
              "app/revoman.jar",
            )
          benchmarkClasspath.drop(2) shouldContainExactlyInAnyOrder
            expectedBenchmarkLibraries()
          orderedPaths(classpath, "runnerClasspath") shouldBe
            listOf(
              "runner/performance-runner.jar",
              "runner/lib/runner-dependency.jar",
            )
          classpath shouldContain "embedded:app/revoman.jar"
          classpath shouldContain sha256(project.embeddedDependency)
          classpath shouldNotContain "kotest"

          val provenance = readUtf8(distribution.resolve("metadata/provenance.json"))
          provenance shouldContain "\"treatment\""
          provenance shouldContain "\"immutableHarness\""
          provenance shouldContain "\"distributionFreezer\""
          provenance shouldContain treatment.gitSha
          provenance shouldContain project.gitSha()

          val protocol = readUtf8(distribution.resolve("metadata/protocol.json"))
          listOf(
              "source/build.gradle",
              "source/settings.gradle",
              "source/gradlew",
              "source/gradlew.bat",
              "source/gradle/wrapper/gradle-wrapper.properties",
              "source/scripts/performance/run",
              "source/src/jmh/java/example/FixtureBenchmark.java",
              "source/fixture-inputs/implementation/build-logic/PerformanceMeasurementPlugin.kt",
              "source/fixture-inputs/implementation/runner/RunnerEngine.kt",
              "source/fixture-inputs/fixture-resources/performance/build.gradle",
              "source/fixture-inputs/protocol/schemas/distribution-protocol-v1.schema.json",
            )
            .forEach(protocol::shouldContain)
          listOf(
              "gradle",
              "javaExecutableSha256",
              "javaFeature",
              "jmhCore",
              "jmhGradlePlugin",
              "kotlinCompiler",
              "runtimeImage",
            )
            .forEach { identity -> protocol shouldContain "\"$identity\"" }

          val launcher = readUtf8(distribution.resolve("bin/performance-runner"))
          launcher shouldContain "/opt/java/openjdk/bin/java"
          launcher shouldContain "runner/performance-runner.jar:runner/lib/runner-dependency.jar"
          launcher shouldNotContain "*"
        }
      }

      mapOf(
          "capture runner" to { project: PerformanceTestProject ->
            Files.writeString(project.root.resolve("uncommitted.txt"), "dirty\n")
          },
          "treatment" to { _: PerformanceTestProject -> Unit },
        )
        .forEach { (role, dirtyCapture) ->
          test("a dirty $role tree fails without publishing a distribution") {
            PerformanceTestProject.create().use { project ->
              val treatment = project.treatment("dirty")
              if (role == "capture runner") {
                dirtyCapture(project)
              } else {
                Files.writeString(treatment.root.resolve("uncommitted.txt"), "dirty\n")
              }
              val distribution = project.root.resolve("build/performance/dirty-$role")

              val result =
                project.buildAndFail(
                  "assemblePerformanceDistribution",
                  "-PperformanceTreatmentSource=${treatment.root}",
                  "-PperformanceTreatmentJar=${treatment.jar}",
                  "-PperformanceDistributionDirectory=$distribution",
                )

              result.output shouldContain "source tree must be clean"
              Files.exists(distribution) shouldBe false
            }
          }
        }

      test("candidate freeze reuses the validated baseline harness byte for byte") {
        PerformanceTestProject.create().use { project ->
          val baselineTreatment = project.treatment("baseline")
          val candidateTreatment = project.treatment("candidate")
          val baseline = project.root.resolve("build/performance/baseline")
          val candidate = project.root.resolve("build/performance/candidate")

          project.build(
            "assemblePerformanceDistribution",
            "-PperformanceTreatmentSource=${baselineTreatment.root}",
            "-PperformanceTreatmentJar=${baselineTreatment.jar}",
            "-PperformanceDistributionDirectory=$baseline",
          )
          project.build(
            "assemblePerformanceDistribution",
            "verifyPerformanceDistribution",
            "-PperformanceTreatmentSource=${candidateTreatment.root}",
            "-PperformanceTreatmentJar=${candidateTreatment.jar}",
            "-PperformanceHarnessFrom=$baseline",
            "-PperformanceDistributionDirectory=$candidate",
          )

          changedFiles(baseline, candidate) shouldBe
            setOf(
              "app/revoman.jar",
              "metadata/classpath.json",
              "metadata/provenance.json",
              "metadata/distribution.sha256",
            )
          val provenance = readUtf8(candidate.resolve("metadata/provenance.json"))
          provenance shouldContain candidateTreatment.gitSha
          provenance shouldContain project.gitSha()
          provenance shouldNotContain baselineTreatment.gitSha
        }
      }

      test("candidate freeze rejects a baseline whose frozen harness no longer validates") {
        PerformanceTestProject.create().use { project ->
          val baselineTreatment = project.treatment("baseline")
          val candidateTreatment = project.treatment("candidate")
          val baseline = project.root.resolve("build/performance/baseline")
          val candidate = project.root.resolve("build/performance/rejected-candidate")

          project.build(
            "assemblePerformanceDistribution",
            "-PperformanceTreatmentSource=${baselineTreatment.root}",
            "-PperformanceTreatmentJar=${baselineTreatment.jar}",
            "-PperformanceDistributionDirectory=$baseline",
          )
          Files.writeString(baseline.resolve("protocol/profiles/canary.json"), "tampered\n")

          val result =
            project.buildAndFail(
              "assemblePerformanceDistribution",
              "-PperformanceTreatmentSource=${candidateTreatment.root}",
              "-PperformanceTreatmentJar=${candidateTreatment.jar}",
              "-PperformanceHarnessFrom=$baseline",
              "-PperformanceDistributionDirectory=$candidate",
            )

          result.output shouldContain "baseline distribution is invalid"
          Files.exists(candidate) shouldBe false
        }
      }

      test("candidate structured diff rejects non-derived metadata changes") {
        PerformanceTestProject.create().use { project ->
          val baselineTreatment = project.treatment("baseline")
          val candidateTreatment = project.treatment("candidate")
          val baseline = project.root.resolve("build/performance/baseline")
          val candidate = project.root.resolve("build/performance/candidate")

          project.build(
            "assemblePerformanceDistribution",
            "-PperformanceTreatmentSource=${baselineTreatment.root}",
            "-PperformanceTreatmentJar=${baselineTreatment.jar}",
            "-PperformanceDistributionDirectory=$baseline",
          )
          project.build(
            "assemblePerformanceDistribution",
            "-PperformanceTreatmentSource=${candidateTreatment.root}",
            "-PperformanceTreatmentJar=${candidateTreatment.jar}",
            "-PperformanceHarnessFrom=$baseline",
            "-PperformanceDistributionDirectory=$candidate",
          )

          val classpathPath = candidate.resolve("metadata/classpath.json")
          val originalClasspath = Files.readAllBytes(classpathPath)
          val tamperedClasspath =
            readUtf8(classpathPath)
              .replace("runner:runner-dependency", "runner:tampered-dependency")
          tamperedClasspath.encodeToByteArray().contentEquals(originalClasspath) shouldNotBe true
          Files.writeString(
            classpathPath,
            tamperedClasspath,
          )
          shouldThrow<IllegalArgumentException> {
              CandidateDistributionDiff.requireAllowed(baseline, candidate)
            }
            .message shouldContain "classpath"
          Files.write(classpathPath, originalClasspath)

          val provenancePath = candidate.resolve("metadata/provenance.json")
          val originalProvenance = Files.readAllBytes(provenancePath)
          val provenance = CanonicalJson.parseStrict(originalProvenance) as ObjectNode
          provenance.get("immutableHarness").asObject().put("gitSha", "0".repeat(40))
          val tamperedProvenance = CanonicalJson.encode(provenance)
          tamperedProvenance.contentEquals(originalProvenance) shouldNotBe true
          Files.write(provenancePath, tamperedProvenance)
          shouldThrow<IllegalArgumentException> {
              CandidateDistributionDiff.requireAllowed(baseline, candidate)
            }
            .message shouldContain "provenance"
          Files.write(provenancePath, originalProvenance)

          val checksumsPath = candidate.resolve("metadata/distribution.sha256")
          val originalChecksums = Files.readAllBytes(checksumsPath)
          val tamperedChecksums =
            readUtf8(checksumsPath).replace(
              Regex("^[0-9a-f]{64}  benchmark/revoman-jmh\\.jar$", RegexOption.MULTILINE),
              "0".repeat(64) + "  benchmark/revoman-jmh.jar",
            )
          tamperedChecksums.encodeToByteArray().contentEquals(originalChecksums) shouldNotBe true
          Files.writeString(
            checksumsPath,
            tamperedChecksums,
          )
          shouldThrow<IllegalArgumentException> {
              CandidateDistributionDiff.requireAllowed(baseline, candidate)
            }
            .message shouldContain "checksum"
          Files.write(checksumsPath, originalChecksums)
        }
      }

      test("container snapshots accept only explicitly verified full source identities") {
        PerformanceTestProject.create().use { project ->
          val captureSha = project.gitSha()
          val treatment = project.treatment("container-snapshot")
          val distribution = project.root.resolve("build/performance/container-snapshot")
          project.removeGitMetadata()
          treatment.removeGitMetadata()

          project.build(
            "assemblePerformanceDistribution",
            "-PperformanceCaptureGitSha=$captureSha",
            "-PperformanceTreatmentGitSha=${treatment.gitSha}",
            "-PperformanceTreatmentSource=${treatment.root}",
            "-PperformanceTreatmentJar=${treatment.jar}",
            "-PperformanceDistributionDirectory=$distribution",
          )

          val provenance = readUtf8(distribution.resolve("metadata/provenance.json"))
          provenance shouldContain captureSha
          provenance shouldContain treatment.gitSha
        }
      }

      test("container snapshots reject malformed externally supplied source identities") {
        PerformanceTestProject.create().use { project ->
          val treatment = project.treatment("invalid-identity")
          val distribution = project.root.resolve("build/performance/invalid-identity")

          val result =
            project.buildAndFail(
              "assemblePerformanceDistribution",
              "-PperformanceCaptureGitSha=0123456",
              "-PperformanceTreatmentGitSha=${treatment.gitSha}",
              "-PperformanceTreatmentSource=${treatment.root}",
              "-PperformanceTreatmentJar=${treatment.jar}",
              "-PperformanceDistributionDirectory=$distribution",
            )

          result.output shouldContain "source Git identity is invalid"
          Files.exists(distribution) shouldBe false
        }
      }
    },
  )

private fun expectedDistributionFiles(): Set<String> =
  setOf(
    "app/revoman.jar",
    "benchmark/revoman-jmh.jar",
    "bin/performance-runner",
    "bin/performance-runner.bat",
    "lib/runtime-dependency.jar",
    "lib/asm-9.0.jar",
    "lib/commons-math3-3.6.1.jar",
    "lib/jmh-core-1.37.jar",
    "lib/jmh-generator-asm-1.37.jar",
    "lib/jmh-generator-bytecode-1.37.jar",
    "lib/jmh-generator-reflection-1.37.jar",
    "lib/jopt-simple-5.0.4.jar",
    "metadata/classpath.json",
    "metadata/distribution.sha256",
    "metadata/protocol.json",
    "metadata/provenance.json",
    "protocol/adapter/run",
    "protocol/expected-cells.json",
    "protocol/profiles/canary.json",
    "protocol/profiles/cold.json",
    "protocol/profiles/warm.json",
    "protocol/qualification/github-hosted.json",
    "protocol/qualification/m4max-docker.json",
    "protocol/runtime/github-hosted.json",
    "protocol/runtime/linux-arm64.json",
    "protocol/runtime/m4max-docker.json",
    "protocol/schemas/capture-provisional-v1.schema.json",
    "protocol/schemas/capture-profile-family-v1.schema.json",
    "protocol/schemas/capture-v1.schema.json",
    "protocol/schemas/distribution-classpath-v1.schema.json",
    "protocol/schemas/distribution-provenance-v1.schema.json",
    "protocol/schemas/distribution-protocol-v1.schema.json",
    "protocol/schemas/expected-cells-v1.schema.json",
    "protocol/schemas/postflight-v1.schema.json",
    "protocol/schemas/preflight-v1.schema.json",
    "protocol/schemas/profiler-summary-v1.schema.json",
    "protocol/schemas/restoration-v1.schema.json",
    "protocol/schemas/watcher-v1.schema.json",
    "protocol/test-vectors/bootstrap-v1.json",
    "runner/lib/runner-dependency.jar",
    "runner/performance-runner.jar",
  )

private fun expectedBenchmarkLibraries(): List<String> =
  listOf(
    "lib/runtime-dependency.jar",
    "lib/jmh-core-1.37.jar",
    "lib/jmh-generator-bytecode-1.37.jar",
    "lib/jmh-generator-reflection-1.37.jar",
    "lib/jmh-generator-asm-1.37.jar",
    "lib/jopt-simple-5.0.4.jar",
    "lib/commons-math3-3.6.1.jar",
    "lib/asm-9.0.jar",
  )

private fun distributionFiles(root: Path): Set<String> =
  Files.walk(root).use { paths ->
    paths
      .filter(Files::isRegularFile)
      .map(root::relativize)
      .map { path -> path.joinToString("/") }
      .toList()
      .toSet()
  }

private fun changedFiles(baseline: Path, candidate: Path): Set<String> =
  distributionFiles(baseline)
    .union(distributionFiles(candidate))
    .filterTo(mutableSetOf()) { relative ->
      val baselineFile = baseline.resolve(relative)
      val candidateFile = candidate.resolve(relative)
      !Files.exists(baselineFile) ||
        !Files.exists(candidateFile) ||
        !Files.readAllBytes(baselineFile).contentEquals(Files.readAllBytes(candidateFile))
    }

private fun orderedPaths(document: String, field: String): List<String> {
  val body =
    checkNotNull(
      Regex("\\\"$field\\\":\\[(.*?)]").find(document)?.groupValues?.get(1),
    ) { "missing $field" }
  return Regex("\\\"path\\\":\\\"([^\\\"]+)\\\"")
    .findAll(body)
    .map { match -> match.groupValues[1] }
    .toList()
}
