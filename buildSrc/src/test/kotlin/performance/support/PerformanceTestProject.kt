/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.support

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Comparator
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

internal class PerformanceTestProject private constructor(val root: Path) : AutoCloseable {
  val inputs: Path = root.resolve("fixture-inputs")
  val embeddedDependency: Path = inputs.resolve("embedded-dependency.jar")
  val defaultDistribution: Path = root.resolve("build/performance/distribution")

  fun build(vararg arguments: String): BuildResult = runner(arguments.toList()).build()

  fun buildAndFail(vararg arguments: String): BuildResult = runner(arguments.toList()).buildAndFail()

  fun treatment(version: String): TreatmentRepository {
    val directory = Files.createTempDirectory(root.parent, "performance-treatment-$version.")
    val jar = directory.resolve("artifact/revoman.jar")
    jar.parent.createDirectories()
    createJar(
      jar,
      mapOf(
        "fixture/embedded/Embedded.class" to compiledClass("fixture.embedded.Embedded"),
        "treatment-version.txt" to "$version\n".encodeToByteArray(),
      ),
    )
    directory.resolve("source.txt").writeText("treatment $version\n")
    directory.resolve(".gitignore").writeText(".gradle/\nbuild/\n")
    initializeGit(directory)
    return TreatmentRepository(root = directory, jar = jar, gitSha = git(directory, "rev-parse", "HEAD"))
  }

  fun gitSha(): String = git(root, "rev-parse", "HEAD")

  fun removeGitMetadata() {
    deleteTree(root.resolve(".git"))
  }

  override fun close() {
    deleteTree(root.parent)
  }

  private fun runner(arguments: List<String>): GradleRunner =
    GradleRunner.create()
      .withProjectDir(root.toFile())
      .withPluginClasspath()
      .withArguments(
        listOf(
          "-q",
          "--stacktrace",
        ) + arguments,
      )

  companion object {
    fun create(template: String = "performance"): PerformanceTestProject {
      val sourceRoot = findSourceRoot()
      val parent = Files.createTempDirectory("revoman-performance-test.")
      val root = parent.resolve("project")
      copyTree(sourceRoot.resolve("buildSrc/src/test/resources/fixtures/$template"), root)
      val project = PerformanceTestProject(root)
      project.installInputs(sourceRoot)
      initializeGit(root)
      return project
    }

    private fun findSourceRoot(): Path =
      generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()) { path ->
          path.parent
        }
        .first { candidate ->
          candidate.resolve("AGENTS.md").exists() && candidate.resolve("buildSrc").exists()
        }

    private fun copyTree(source: Path, target: Path) {
      Files.walk(source).use { paths ->
        paths.forEach { path ->
          val destination = target.resolve(source.relativize(path).toString())
          when {
            Files.isDirectory(path) -> destination.createDirectories()
            else -> {
              destination.parent.createDirectories()
              Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES)
            }
          }
        }
      }
    }

    private fun initializeGit(directory: Path) {
      git(directory, "init", "-q")
      git(directory, "config", "user.name", "Performance Fixture")
      git(directory, "config", "user.email", "performance-fixture@example.invalid")
      git(directory, "add", ".")
      git(directory, "commit", "-q", "-m", "fixture")
    }

    private fun git(directory: Path, vararg arguments: String): String {
      val process =
        ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments)
          .redirectErrorStream(true)
          .start()
      val output = process.inputStream.readAllBytes().decodeToString().trim()
      check(process.waitFor() == 0) { "fixture git failed: $output" }
      return output
    }

    private fun deleteTree(root: Path) {
      if (!root.exists()) return
      Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }

    private fun createJar(
      target: Path,
      entries: Map<String, ByteArray>,
      multiRelease: Boolean = false,
    ) {
      target.parent.createDirectories()
      val manifest =
        Manifest().also { value ->
          value.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
          if (multiRelease) value.mainAttributes.putValue("Multi-Release", "true")
        }
      JarOutputStream(Files.newOutputStream(target), manifest).use { jar ->
        entries.toSortedMap().forEach { (name, bytes) ->
          jar.putNextEntry(JarEntry(name).also { it.time = 0L })
          jar.write(bytes)
          jar.closeEntry()
        }
      }
    }

    private fun compiledClass(binaryName: String): ByteArray {
      val sourceDirectory = Files.createTempDirectory("performance-fixture-class.")
      try {
        val packageName = binaryName.substringBeforeLast('.', missingDelimiterValue = "")
        val simpleName = binaryName.substringAfterLast('.')
        val source = sourceDirectory.resolve("source/${binaryName.replace('.', '/')}.java")
        val classes = sourceDirectory.resolve("classes")
        source.parent.createDirectories()
        classes.createDirectories()
        source.writeText(
          buildString {
            if (packageName.isNotEmpty()) append("package $packageName;\n")
            append("public final class $simpleName {}\n")
          },
        )
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        val exitCode =
          compiler.run(
            null,
            OutputStream.nullOutputStream(),
            OutputStream.nullOutputStream(),
            "--release",
            Runtime.version().feature().toString(),
            "-g:none",
            "-d",
            classes.toString(),
            source.toString(),
          )
        check(exitCode == 0) { "fixture Java compilation failed" }
        return Files.readAllBytes(classes.resolve(binaryName.replace('.', '/') + ".class"))
      } finally {
        deleteTree(sourceDirectory)
      }
    }
  }

  private fun installInputs(sourceRoot: Path) {
    inputs.createDirectories()
    createJar(
      inputs.resolve("runtime-dependency.jar"),
      mapOf(
        "fixture/runtime/RuntimeDependency.class" to
          compiledClass("fixture.runtime.RuntimeDependency"),
        "META-INF/versions/21/fixture/runtime/RuntimeDependency.class" to
          compiledClass("fixture.runtime.RuntimeDependency"),
        "org/slf4j/simple/SimpleLogger.class" to compiledClass("org.slf4j.simple.SimpleLogger"),
      ),
      multiRelease = true,
    )
    createJar(
      embeddedDependency,
      mapOf("fixture/embedded/Embedded.class" to compiledClass("fixture.embedded.Embedded")),
    )
    createJar(
      inputs.resolve("kotest-leak.jar"),
      mapOf("io/kotest/Fake.class" to compiledClass("io.kotest.Fake")),
    )
    installRunnerDistribution()
    installProtocol(sourceRoot)
    installProtocolSources(sourceRoot)
  }

  private fun installRunnerDistribution() {
    val runnerRoot = inputs.resolve("runner-dist")
    runnerRoot.resolve("bin").createDirectories()
    runnerRoot.resolve("lib").createDirectories()
    runnerRoot.resolve("bin/performance-runner").apply {
      writeText("#!/bin/sh\nexit 0\n")
      Files.setPosixFilePermissions(
        this,
        setOf(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE,
        ),
      )
    }
    runnerRoot.resolve("bin/performance-runner.bat").writeText("@exit /b 0\r\n")
    createJar(
      runnerRoot.resolve("lib/performance-runner.jar"),
      mapOf(
        "performance/runner/FixtureRunner.class" to
          compiledClass("performance.runner.FixtureRunner"),
      ),
    )
    createJar(
      runnerRoot.resolve("lib/runner-dependency.jar"),
      mapOf(
        "fixture/runner/RunnerDependency.class" to
          compiledClass("fixture.runner.RunnerDependency"),
      ),
    )
  }

  private fun installProtocol(sourceRoot: Path) {
    val protocol = inputs.resolve("protocol")
    val schemas = protocol.resolve("schemas")
    val sourceSchemas =
      sourceRoot.resolve("buildSrc/performance-runner/src/main/resources/performance/protocol/schemas")
    copyTree(sourceSchemas, schemas)
    mapOf(
        "profiles/canary.json" to "{}\n",
        "profiles/cold.json" to "{}\n",
        "profiles/warm.json" to "{}\n",
        "runtime/linux-arm64.json" to "{}\n",
        "runtime/m4max-docker.json" to "{}\n",
        "runtime/github-hosted.json" to "{}\n",
        "qualification/m4max-docker.json" to "{}\n",
        "qualification/github-hosted.json" to "{}\n",
        "test-vectors/bootstrap-v1.json" to "{}\n",
        "expected-cells.json" to "{}\n",
      )
      .forEach { (relativePath, contents) ->
        protocol.resolve(relativePath).also { path ->
          path.parent.createDirectories()
          path.writeText(contents)
        }
      }
    val adapter = protocol.resolve("adapter/run")
    adapter.parent.createDirectories()
    Files.copy(
      sourceRoot.resolve("scripts/performance/run"),
      adapter,
      StandardCopyOption.COPY_ATTRIBUTES,
    )
  }

  private fun installProtocolSources(sourceRoot: Path) {
    listOf("gradlew", "gradlew.bat").forEach { name ->
      Files.copy(
        sourceRoot.resolve(name),
        root.resolve(name),
        StandardCopyOption.COPY_ATTRIBUTES,
      )
    }
    copyTree(sourceRoot.resolve("gradle/wrapper"), root.resolve("gradle/wrapper"))

    val adapter = root.resolve("scripts/performance/run")
    adapter.parent.createDirectories()
    Files.copy(
      sourceRoot.resolve("scripts/performance/run"),
      adapter,
      StandardCopyOption.COPY_ATTRIBUTES,
    )

    mapOf(
        sourceRoot.resolve(
          "buildSrc/src/main/kotlin/performance/PerformanceMeasurementPlugin.kt",
        ) to inputs.resolve("implementation/build-logic/PerformanceMeasurementPlugin.kt"),
        sourceRoot.resolve(
          "buildSrc/performance-runner/src/main/kotlin/performance/runner/RunnerEngine.kt",
        ) to inputs.resolve("implementation/runner/RunnerEngine.kt"),
        sourceRoot.resolve("buildSrc/src/test/resources/fixtures/performance/build.gradle") to
          inputs.resolve("fixture-resources/performance/build.gradle"),
      )
      .forEach { (source, destination) ->
        destination.parent.createDirectories()
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
      }
  }
}

internal data class TreatmentRepository(
  val root: Path,
  val jar: Path,
  val gitSha: String,
) {
  fun removeGitMetadata() {
    root.resolve(".git").toFile().deleteRecursively()
  }
}

internal fun sha256(path: Path): String =
  MessageDigest.getInstance("SHA-256")
    .digest(path.readBytes())
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun readJarEntries(path: Path): Set<String> =
  java.util.jar.JarFile(path.toFile()).use { jar ->
    jar.entries().asSequence().map(JarEntry::getName).toSet()
  }

internal fun readManifestAttribute(path: Path, name: String): String? =
  java.util.jar.JarFile(path.toFile()).use { jar -> jar.manifest?.mainAttributes?.getValue(name) }

internal fun readUtf8(path: Path): String = Files.readString(path, StandardCharsets.UTF_8)
