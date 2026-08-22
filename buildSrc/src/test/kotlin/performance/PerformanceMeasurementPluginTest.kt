/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.testkit.runner.TaskOutcome
import performance.support.PerformanceTestProject
import performance.support.readJarEntries
import performance.support.readManifestAttribute

class PerformanceMeasurementPluginTest :
  FunSpec(
    {
      test("the legacy flattened JMH path captures the fail-open defect") {
        PerformanceTestProject.create("performance-legacy").use { project ->
          project.build("jmhJar")
          val flattened =
            project.root.resolve("build/libs/performance-legacy-fixture-jmh.jar")
          val entries = readJarEntries(flattened)

          entries shouldContain "example/FixtureApplication.class"
          entries shouldContain "example/FixtureBenchmark.class"
          entries shouldContain "example/LeakingTestClass.class"
          entries shouldContain "io/kotest/Fake.class"
          entries shouldContain "org/slf4j/simple/SimpleLogger.class"
          entries shouldContain "META-INF/versions/21/fixture/runtime/RuntimeDependency.class"
          readManifestAttribute(flattened, "Multi-Release") shouldBe null

          project.build("jmh")
          Files.readString(project.root.resolve("build/results/jmh/results.json"))
            .replace(Regex("\\s"), "") shouldBe "[]"
        }
      }

      test("the supported plugin excludes tests before generation and preserves benchmark isolation") {
        PerformanceTestProject.create().use { project ->
          project.build("assertPerformanceIsolation", "performanceBenchmarkJar")
          val benchmarkJar = project.root.resolve("build/performance/jars/revoman-jmh.jar")
          val entries = readJarEntries(benchmarkJar)

          entries shouldContain "example/FixtureBenchmark.class"
          entries shouldContain "META-INF/BenchmarkList"
          entries shouldContain "META-INF/CompilerHints"
          entries shouldNotContain "example/FixtureApplication.class"
          entries shouldNotContain "example/LeakingTestClass.class"
          entries shouldNotContain "io/kotest/Fake.class"
          entries shouldNotContain "org/slf4j/simple/SimpleLogger.class"
          entries.none { it.startsWith("META-INF/versions/") } shouldBe true
        }
      }

      test("protocol manifest root is path context rather than a recursive task input") {
        val getter =
          GenerateProtocolManifestTask::class.java.getMethod("getCaptureRunnerSourceDirectory")
        val mappingGetter =
          GenerateProtocolManifestTask::class.java.getMethod("getProtocolSourceLogicalPaths")
        val dependenciesGetter =
          GenerateProtocolManifestTask::class.java.getMethod("getBenchmarkDependencies")

        getter.isAnnotationPresent(Internal::class.java) shouldBe true
        getter.isAnnotationPresent(InputDirectory::class.java) shouldBe false
        mappingGetter.isAnnotationPresent(Input::class.java) shouldBe true
        dependenciesGetter.isAnnotationPresent(Classpath::class.java) shouldBe false
        dependenciesGetter.isAnnotationPresent(InputFiles::class.java) shouldBe true
        dependenciesGetter.getAnnotation(PathSensitive::class.java).value shouldBe
          PathSensitivity.NAME_ONLY
      }

      test("protocol manifest invalidates when its root-relative source mapping changes") {
        PerformanceTestProject.create().use { project ->
          val manifest = project.root.resolve("build/performance/protocol/closure.json")
          project.build(
            "generatePerformanceProtocolManifest",
            "--build-cache",
            "-PfixtureCaptureRoot=.",
          )
          val projectRelativeManifest = Files.readString(manifest)

          val remapped =
            project.build(
              "generatePerformanceProtocolManifest",
              "--build-cache",
              "-PfixtureCaptureRoot=..",
            )
          val parentRelativeManifest = Files.readString(manifest)

          remapped.task(":generatePerformanceProtocolManifest")?.outcome shouldBe
            TaskOutcome.SUCCESS
          parentRelativeManifest shouldNotBe projectRelativeManifest
          parentRelativeManifest shouldContain "source/project/build.gradle"

          val unchanged =
            project.build(
              "generatePerformanceProtocolManifest",
              "--build-cache",
              "-PfixtureCaptureRoot=..",
            )
          unchanged.task(":generatePerformanceProtocolManifest")?.outcome shouldBe
            TaskOutcome.UP_TO_DATE
        }
      }

      test("protocol manifest invalidates when a benchmark dependency is renamed") {
        PerformanceTestProject.create().use { project ->
          val manifest = project.root.resolve("build/performance/protocol/closure.json")
          project.build(
            "generatePerformanceProtocolManifest",
            "--build-cache",
            "-PfixtureRuntimeDependency=fixture-inputs/runtime-dependency.jar",
          )
          val originalManifest = Files.readString(manifest)
          Files.copy(
            project.inputs.resolve("runtime-dependency.jar"),
            project.inputs.resolve("renamed-dependency.jar"),
          )

          val renamed =
            project.build(
              "generatePerformanceProtocolManifest",
              "--build-cache",
              "-PfixtureRuntimeDependency=fixture-inputs/renamed-dependency.jar",
            )
          val renamedManifest = Files.readString(manifest)

          renamed.task(":generatePerformanceProtocolManifest")?.outcome shouldBe
            TaskOutcome.SUCCESS
          renamedManifest shouldNotBe originalManifest
          renamedManifest shouldContain "dependencies/renamed-dependency.jar"
        }
      }

      test("protocol manifest invalidates when dependency jar bytes are repacked") {
        PerformanceTestProject.create().use { project ->
          val dependency = project.inputs.resolve("mutable-dependency.jar")
          Files.copy(project.inputs.resolve("runtime-dependency.jar"), dependency)
          val manifest = project.root.resolve("build/performance/protocol/closure.json")
          project.build(
            "generatePerformanceProtocolManifest",
            "--build-cache",
            "-PfixtureRuntimeDependency=fixture-inputs/mutable-dependency.jar",
          )
          val originalManifest = Files.readString(manifest)
          val originalBytes = Files.readAllBytes(dependency)

          repackJar(dependency)
          Files.readAllBytes(dependency).contentEquals(originalBytes) shouldBe false
          val repacked =
            project.build(
              "generatePerformanceProtocolManifest",
              "--build-cache",
              "-PfixtureRuntimeDependency=fixture-inputs/mutable-dependency.jar",
            )
          val repackedManifest = Files.readString(manifest)

          repacked.task(":generatePerformanceProtocolManifest")?.outcome shouldBe
            TaskOutcome.SUCCESS
          repackedManifest shouldNotBe originalManifest
        }
      }

      listOf("jmh", "jmhJar").forEach { legacyTask ->
        test("direct $legacyTask fails with supported migration guidance") {
          PerformanceTestProject.create().use { project ->
            val result = project.buildAndFail(legacyTask)

            result.output shouldContain "scripts/performance/run"
            result.output shouldContain "unsupported flattened JMH task"
          }
        }
      }
    },
  )

private fun repackJar(path: Path) {
  val entries =
    JarFile(path.toFile()).use { jar ->
      jar.entries().asSequence().map { entry -> entry.name to jar.getInputStream(entry).readAllBytes() }.toList()
    }
  JarOutputStream(Files.newOutputStream(path)).use { output ->
    entries.asReversed().forEach { (name, bytes) ->
      output.putNextEntry(JarEntry(name).also { it.time = 1_234L })
      output.write(bytes)
      output.closeEntry()
    }
  }
}
