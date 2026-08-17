/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance

import java.nio.file.Files
import java.nio.file.Path
import me.champeau.jmh.JmhBytecodeGeneratorTask
import me.champeau.jmh.JmhParameters
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.util.GradleVersion
import performance.hash.Sha256

/** Inputs owned by the private performance-distribution assembly seam. */
abstract class PerformanceMeasurementExtension {
  abstract val captureRunnerSourceDirectory: DirectoryProperty

  abstract val captureGitSha: Property<String>

  abstract val treatmentSourceDirectory: DirectoryProperty

  abstract val treatmentGitSha: Property<String>

  abstract val treatmentJar: RegularFileProperty

  abstract val runnerDistributionDirectory: DirectoryProperty

  abstract val protocolSchemaDirectory: DirectoryProperty

  abstract val profileDirectory: DirectoryProperty

  abstract val runtimeDirectory: DirectoryProperty

  abstract val qualificationPolicyDirectory: DirectoryProperty

  abstract val testVectorDirectory: DirectoryProperty

  abstract val expectedCells: RegularFileProperty

  abstract val adapter: RegularFileProperty

  abstract val embeddedDependency: RegularFileProperty

  abstract val embeddedDependencyCoordinate: Property<String>

  abstract val expectedBenchmarks: ListProperty<String>

  abstract val protocolSources: ConfigurableFileCollection

  abstract val distributionDirectory: DirectoryProperty

  abstract val harnessFrom: DirectoryProperty
}

/** Installs the only supported, classpath-preserving performance build path. */
class PerformanceMeasurementPlugin : Plugin<Project> {
  override fun apply(project: Project) =
    with(project) {
      pluginManager.apply("me.champeau.jmh")
      val jmh = extensions.getByType(JmhParameters::class.java)
      jmh.includeTests.set(false)
      jmh.failOnError.set(true)
      jmh.jmhVersion.set(JMH_CORE_VERSION)

      val sourceSets = extensions.getByType(SourceSetContainer::class.java)
      val jmhSourceSet = sourceSets.getByName("jmh")
      tasks.named("jmhRunBytecodeGenerator", JmhBytecodeGeneratorTask::class.java).configure {
        classesDirsToProcess.setFrom(jmhSourceSet.output.classesDirs)
        runtimeClasspath.setFrom(jmhSourceSet.runtimeClasspath)
      }
      tasks.named("jmhCompileGeneratedClasses", JavaCompile::class.java).configure {
        classpath = jmhSourceSet.runtimeClasspath
      }

      val extension =
        extensions.create("performanceMeasurement", PerformanceMeasurementExtension::class.java)
      extension.captureRunnerSourceDirectory.convention(layout.projectDirectory)
      extension.captureGitSha.convention(providers.gradleProperty("performanceCaptureGitSha"))
      extension.treatmentSourceDirectory.convention(layout.projectDirectory)
      extension.treatmentGitSha.convention(providers.gradleProperty("performanceTreatmentGitSha"))
      extension.runnerDistributionDirectory.convention(
        layout.projectDirectory.dir("buildSrc/performance-runner/build/install/performance-runner"),
      )
      extension.protocolSchemaDirectory.convention(
        layout.projectDirectory.dir(
          "buildSrc/performance-runner/src/main/resources/performance/protocol/schemas",
        ),
      )
      extension.distributionDirectory.convention(
        layout.buildDirectory.dir("performance/distribution"),
      )
      extension.embeddedDependencyCoordinate.convention(
        "org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.1",
      )

      val benchmarkJar =
        tasks.register("performanceBenchmarkJar", PerformanceBenchmarkJarTask::class.java) {
          group = "performance"
          description = "Packages benchmark classes and generated JMH metadata without flattening"
          dependsOn("jmhClasses", "jmhCompileGeneratedClasses")
          destinationDirectory.set(layout.buildDirectory.dir("performance/jars"))
          from(jmhSourceSet.output)
          from(layout.buildDirectory.dir("jmh-generated-classes"))
          from(layout.buildDirectory.dir("jmh-generated-resources"))
        }

      val runtimeClasspath = configurations.getByName("jmhRuntimeClasspath")
      val closure =
        tasks.register("generatePerformanceProtocolManifest", GenerateProtocolManifestTask::class.java) {
          group = "performance"
          description = "Generates the treatment-free measurement protocol closure"
          dependsOn(benchmarkJar)
          captureRunnerSourceDirectory.set(extension.captureRunnerSourceDirectory)
          protocolSources.from(extension.protocolSources)
          this.benchmarkJar.set(benchmarkJar.flatMap(PerformanceBenchmarkJarTask::getArchiveFile))
          runnerDistributionDirectory.set(extension.runnerDistributionDirectory)
          benchmarkDependencies.from(runtimeClasspath)
          toolIdentities.set(
            providers.provider {
              val java = currentJavaExecutable()
              mapOf(
                "gradle" to GradleVersion.current().version,
                "javaExecutableSha256" to Sha256.digest(Files.readAllBytes(java)).hex,
                "javaFeature" to Runtime.version().feature().toString(),
                "jmhCore" to JMH_CORE_VERSION,
                "jmhGradlePlugin" to JMH_GRADLE_PLUGIN_VERSION,
                "kotlinCompiler" to KotlinVersion.CURRENT.toString(),
                "runtimeImage" to RUNTIME_IMAGE,
              )
            },
          )
          manifestFile.set(layout.buildDirectory.file("performance/protocol/closure.json"))
        }

      val assemble =
        tasks.register(
          "assemblePerformanceDistribution",
          AssemblePerformanceDistributionTask::class.java,
        ) {
          group = "performance"
          description = "Assembles and validates a classpath-preserving performance distribution"
          dependsOn(benchmarkJar, closure)
          captureRunnerSourceDirectory.set(extension.captureRunnerSourceDirectory)
          captureGitSha.set(extension.captureGitSha)
          treatmentSourceDirectory.set(extension.treatmentSourceDirectory)
          treatmentGitSha.set(extension.treatmentGitSha)
          treatmentJar.set(extension.treatmentJar)
          this.benchmarkJar.set(benchmarkJar.flatMap(PerformanceBenchmarkJarTask::getArchiveFile))
          benchmarkDependencies.from(runtimeClasspath)
          runnerDistributionDirectory.set(extension.runnerDistributionDirectory)
          protocolSchemaDirectory.set(extension.protocolSchemaDirectory)
          profileDirectory.set(extension.profileDirectory)
          runtimeDirectory.set(extension.runtimeDirectory)
          qualificationPolicyDirectory.set(extension.qualificationPolicyDirectory)
          testVectorDirectory.set(extension.testVectorDirectory)
          expectedCells.set(extension.expectedCells)
          adapter.set(extension.adapter)
          embeddedDependency.set(extension.embeddedDependency)
          embeddedDependencyCoordinate.set(extension.embeddedDependencyCoordinate)
          expectedBenchmarks.set(extension.expectedBenchmarks)
          protocolClosureManifest.set(closure.flatMap { it.manifestFile })
          harnessFrom.set(extension.harnessFrom)
          distributionDirectory.set(extension.distributionDirectory)
        }

      tasks.register(
        "verifyPerformanceDistribution",
        VerifyPerformanceDistributionTask::class.java,
      ) {
        group = "performance"
        description = "Revalidates the assembled performance distribution"
        dependsOn(assemble)
        distributionDirectory.set(extension.distributionDirectory)
      }

      listOf("jmh", "jmhJar").forEach { taskName ->
        tasks.named(taskName).configure {
          doFirst {
            throw GradleException(
              "unsupported flattened JMH task '$taskName'; use scripts/performance/run",
            )
          }
        }
      }
    }

  private companion object {
    const val JMH_CORE_VERSION = "1.37"
    const val JMH_GRADLE_PLUGIN_VERSION = "0.7.3"
    const val RUNTIME_IMAGE =
      "docker.io/library/eclipse-temurin@sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e"

    fun currentJavaExecutable(): Path =
      Path.of(
          checkNotNull(ProcessHandle.current().info().command().orElse(null)) {
            "current Java executable is unavailable"
          },
        )
        .toAbsolutePath()
        .normalize()
  }
}
