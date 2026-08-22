/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.support

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import performance.hash.Sha256

internal data class AdapterInvocation(
  val exitCode: Int,
  val standardOutput: String,
  val standardError: String,
  val commands: List<List<String>>,
)

internal class FakeHost : AutoCloseable {
  val sourceRoot: Path = findSourceRoot()
  val repositoryRoot: Path = Files.createTempDirectory("revoman-adapter-test.")
  val script: Path = repositoryRoot.resolve("scripts/performance/run")
  val artifactRoot: Path = repositoryRoot.resolve("build/performance")
  val reviewedArtifactRoot: Path = repositoryRoot.resolve("docs/superpowers/benchmarks")

  private val fakeBin: Path = repositoryRoot.resolve("fake-bin")
  private val commandLog: Path = repositoryRoot.resolve("fake-host.log")
  private val fsyncLog: Path = repositoryRoot.resolve(".fake-fsync.log")
  private val rawRuntimeManifest: Path = repositoryRoot.resolve(".fake-runtime-manifest.raw")
  private val publicationFixtures: Path = repositoryRoot.resolve(".fake-publication-fixtures")

  init {
    artifactRoot.createDirectories()
    reviewedArtifactRoot.createDirectories()
    repositoryRoot.resolve("inputs").createDirectories()
    repositoryRoot.resolve("scripts/performance").createDirectories()
    repositoryRoot.resolve("config/performance/runtime").createDirectories()
    repositoryRoot.resolve("config/performance/policies").createDirectories()
    copyProtocolFiles()
    copyRawRuntimeManifest()
    createPublicationFixtures()
    installFakeCommands()
  }

  fun treatmentSource(name: String = "treatment"): Path =
    repositoryRoot.resolve("inputs/$name").also { directory -> directory.createDirectories() }

  fun frozenDistribution(
    name: String,
    matchingAdapter: Boolean = true,
    canonicalAdapterPath: Boolean = true,
  ): Path =
    repositoryRoot.resolve("inputs/$name").also { distribution ->
      val adapterDirectory =
        distribution.resolve(if (canonicalAdapterPath) "protocol/adapter" else "adapter")
      adapterDirectory.createDirectories()
      if (matchingAdapter && script.exists()) {
        Files.copy(script, adapterDirectory.resolve("run"), StandardCopyOption.REPLACE_EXISTING)
      } else {
        adapterDirectory.resolve("run").writeText("mismatched adapter\n")
      }
      distribution.resolve("bin").createDirectories()
      distribution
        .resolve("bin/performance-runner")
        .also { launcher ->
          launcher.writeText("#!/bin/sh\nexit 0\n")
          launcher.toFile().setExecutable(true, true)
        }
      distribution.resolve("metadata").createDirectories()
      distribution.resolve("metadata/distribution.sha256").writeText("fixture\n")
    }

  fun inputDirectory(name: String): Path =
    repositoryRoot.resolve("inputs/$name").also { directory -> directory.createDirectories() }

  fun output(token: String): String = "build/performance/$token"

  fun outputPath(token: String): Path = artifactRoot.resolve(token)

  fun fsyncedPaths(): List<String> = fsyncLog.takeIf(Path::exists)?.readLines().orEmpty()

  fun invoke(
    vararg arguments: String,
    environment: Map<String, String> = emptyMap(),
    functionOverrides: String? = null,
  ): AdapterInvocation {
    Files.deleteIfExists(commandLog)
    val overrides =
      listOfNotNull(FAKE_HOST_FUNCTION_OVERRIDES, functionOverrides).joinToString(separator = "\n")
    val command =
      listOf(
        "/bin/bash",
        "-c",
        "source \"\$1\"; shift; $overrides; main \"\$@\"",
        "adapter-contract-test",
        script.toString(),
        *arguments,
      )

    return run(command, environment)
  }

  fun invokePackaged(vararg arguments: String): AdapterInvocation =
    run(listOf(sourceRoot.resolve("scripts/performance/run").toString(), *arguments), emptyMap())

  fun invokeFakeDocker(vararg arguments: String): AdapterInvocation =
    run(listOf(fakeBin.resolve("docker").toString(), *arguments), emptyMap())

  fun invokeFunction(
    function: String,
    vararg arguments: String,
    environment: Map<String, String> = emptyMap(),
    functionOverrides: String? = null,
    useFakeHostTools: Boolean = true,
  ): AdapterInvocation {
    require(function.matches(Regex("[a-z_]+"))) { "unsafe Bash function name" }
    val overrides =
      listOfNotNull(
        FAKE_HOST_FUNCTION_OVERRIDES.takeIf { useFakeHostTools },
        functionOverrides,
      ).joinToString(separator = "\n")
    val setup = overrides.takeIf(String::isNotBlank)?.let { "$it;" }.orEmpty()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        "source \"\$1\"; shift; $setup $function \"\$@\"",
        "adapter-contract-test",
        script.toString(),
        *arguments,
      ),
      environment,
    )
  }

  private fun run(
    command: List<String>,
    environment: Map<String, String>,
  ): AdapterInvocation {
    Files.deleteIfExists(fsyncLog)
    val standardOutput = Files.createTempFile(repositoryRoot, "stdout.", ".log")
    val standardError = Files.createTempFile(repositoryRoot, "stderr.", ".log")
    resetFakeVolumeState()
    val process =
      ProcessBuilder(command)
        .directory(repositoryRoot.toFile())
        .redirectOutput(standardOutput.toFile())
        .redirectError(standardError.toFile())
    process.environment().apply {
      UNSAFE_HOST_ENVIRONMENT.forEach(::remove)
      put("PATH", "$fakeBin:/usr/bin:/bin:/usr/sbin:/sbin")
      put("FAKE_HOST_LOG", commandLog.toString())
      put("FAKE_FSYNC_LOG", fsyncLog.toString())
      put("FAKE_REPO_ROOT", repositoryRoot.toString())
      put("FAKE_GIT_SHA", "0123456789abcdef0123456789abcdef01234567")
      put("FAKE_GIT_STATUS", "")
      put("ImageVersion", "runner-image_v1+rev.2")
      put("FAKE_DOCKER_RAW_MANIFEST_FILE", rawRuntimeManifest.toString())
      put("FAKE_JAVA_COMMAND", currentJavaCommand())
      put("FAKE_TEST_CLASSPATH", testClasspath())
      put("FAKE_PUBLICATION_FIXTURE_ROOT", publicationFixtures.toString())
      putAll(environment)
    }

    val exitCode = process.start().waitFor()
    return AdapterInvocation(
      exitCode = exitCode,
      standardOutput = standardOutput.readText(),
      standardError = standardError.readText(),
      commands =
        commandLog
          .takeIf(Path::exists)
          ?.readLines()
          ?.map { line -> line.split('\t').map(::decodeHexArgument) }
          .orEmpty(),
    )
  }

  private fun resetFakeVolumeState() {
    listOf(
        ".fake-finalizer-verified",
        ".fake-recovery-complete",
        ".fake-preparation-complete",
        ".fake-finalizer-distribution",
        ".fake-freeze-bootstrap-distribution",
        ".fake-memory-pressure-count",
        ".fake-private-runtime.json",
        ".fake-provisional-distribution",
        ".fake-timed-private-runtime.sha256",
        ".fake-timed-observed",
        ".fake-timed-running",
        ".fake-freeze-validated-distribution",
      )
      .forEach { name -> Files.deleteIfExists(repositoryRoot.resolve(name)) }
    Files.list(repositoryRoot).use { files ->
      files
        .filter { path -> path.fileName.toString().startsWith(".fake-docker-volume-") }
        .forEach(Files::deleteIfExists)
    }
  }

  override fun close() {
    Files.walk(repositoryRoot).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }

  private fun copyProtocolFiles() {
    if (sourceRoot.resolve("scripts/performance/run").exists()) {
      Files.copy(
        sourceRoot.resolve("scripts/performance/run"),
        script,
        StandardCopyOption.REPLACE_EXISTING,
      )
      script.toFile().setExecutable(true, true)
    }
    val sourceRuntime = sourceRoot.resolve("config/performance/runtime")
    if (sourceRuntime.exists()) {
      Files.list(sourceRuntime).use { files ->
        files.filter(Files::isRegularFile).forEach { source ->
          Files.copy(
            source,
            repositoryRoot.resolve("config/performance/runtime/${source.fileName}"),
            StandardCopyOption.REPLACE_EXISTING,
          )
        }
      }
    }
    val sourcePolicies = sourceRoot.resolve("config/performance/policies")
    if (sourcePolicies.exists()) {
      Files.list(sourcePolicies).use { files ->
        files.filter(Files::isRegularFile).forEach { source ->
          Files.copy(
            source,
            repositoryRoot.resolve("config/performance/policies/${source.fileName}"),
            StandardCopyOption.REPLACE_EXISTING,
          )
        }
      }
    }
  }

  private fun copyRawRuntimeManifest() {
    checkNotNull(
        javaClass.getResourceAsStream("/performance/temurin-21-linux-arm64-v1.manifest.json"),
      ) {
        "missing exact raw Temurin manifest fixture"
      }
      .use { input -> Files.copy(input, rawRuntimeManifest, StandardCopyOption.REPLACE_EXISTING) }
  }

  private fun createPublicationFixtures() {
    mapOf(
        "canary" to mapOf("capture.json" to "{}\n"),
        "capture" to mapOf("capture.json" to "{}\n"),
        "compare" to mapOf("comparison.json" to "{}\n", "comparison.md" to "fixture\n"),
        "campaign" to mapOf("campaign.json" to "{}\n"),
        "freeze" to mapOf("metadata/distribution.sha256" to "fixture\n"),
        "invalid" to
          mapOf(
            "INVALID/reason" to "INPUT_OR_PROTOCOL_INVALID\n",
            "stderr.log" to "performance-runner: INPUT_OR_PROTOCOL_INVALID\n",
          ),
      )
      .forEach { (name, files) ->
        val root = publicationFixtures.resolve(name).also(Files::createDirectories)
        files.forEach { (relative, contents) ->
          root.resolve(relative).also { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, contents)
          }
        }
        val manifest =
          files.keys.sorted().joinToString(separator = "\n", postfix = "\n") { relative ->
            "${Sha256.digest(root.resolve(relative)).hex}  $relative"
          }
        Files.writeString(root.resolve("checksums.sha256"), manifest)
      }
  }

  private fun currentJavaCommand(): String =
    checkNotNull(ProcessHandle.current().info().command().orElse(null))

  private fun testClasspath(): String =
    buildList {
        addAll(System.getProperty("java.class.path").split(File.pathSeparator))
        generateSequence(Thread.currentThread().contextClassLoader) { loader -> loader.parent }
          .filterIsInstance<URLClassLoader>()
          .flatMap { loader -> loader.urLs.asSequence() }
          .filter { url -> url.protocol == "file" }
          .map { url -> Path.of(url.toURI()).toString() }
          .forEach(::add)
      }
      .filter(String::isNotBlank)
      .distinct()
      .joinToString(File.pathSeparator)

  private fun installFakeCommands() {
    fakeBin.createDirectories()
    val fakeCommand =
      checkNotNull(javaClass.getResourceAsStream("/performance/fake-host-command.sh")) {
          "missing fake-host-command.sh"
        }
        .use { input ->
          fakeBin.resolve("fake-host-command.sh").also { target ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
          }
        }
    Files.setPosixFilePermissions(
      fakeCommand,
      setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
      ),
    )
    FAKE_COMMANDS.forEach { name ->
      Files.createSymbolicLink(fakeBin.resolve(name), fakeCommand.fileName)
    }
  }

  private fun findSourceRoot(): Path =
    generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()) { path ->
        path.parent
      }
      .first { candidate ->
        candidate.resolve("AGENTS.md").exists() && candidate.resolve("buildSrc").exists()
      }

  companion object {
    private val FAKE_HOST_FUNCTION_OVERRIDES =
      """
      adapter_system_name() { command uname -s; }
      adapter_process_status() { command ps "${'$'}@"; }
      adapter_power_status() { command pmset "${'$'}@"; }
      adapter_backup_status() { command tmutil "${'$'}@"; }
      adapter_memory_pressure() { command memory_pressure "${'$'}@"; }
      adapter_vm_statistics() { command vm_stat "${'$'}@"; }
      adapter_hid_status() { command ioreg "${'$'}@"; }
      adapter_system_control() { command sysctl "${'$'}@"; }
      adapter_caffeinate() { command caffeinate "${'$'}@"; }
      adapter_deadline_sleep_millis() { command sleep 0.005; }
      adapter_sleep_millis() { command sleep "${'$'}1"; }
      adapter_fsync_path() { command printf '%s\n' "${'$'}1" >>"${'$'}FAKE_FSYNC_LOG"; }
      """.trimIndent()

    private val FAKE_COMMANDS =
      listOf(
        "caffeinate",
        "docker",
        "git",
        "gradle",
        "ioreg",
        "java",
        "memory_pressure",
        "osascript",
        "pmset",
        "ps",
        "sleep",
        "sudo",
        "sysctl",
        "tmutil",
        "uname",
        "vm_stat",
        "dzdo",
      )

    private val UNSAFE_HOST_ENVIRONMENT =
      setOf(
        "ALL_PROXY",
        "GITHUB_ENV",
        "GITHUB_PATH",
        "GITHUB_TOKEN",
        "GITHUB_WORKSPACE",
        "GRADLE_OPTS",
        "HTTPS_PROXY",
        "HTTP_PROXY",
        "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "MAVEN_OPTS",
        "NEXUS_PASSWORD",
        "NEXUS_USERNAME",
        "NO_PROXY",
        "RUNNER_NAME",
        "RUNNER_TRACKING_ID",
        "_JAVA_OPTIONS",
      )
  }
}

private fun decodeHexArgument(encoded: String): String {
  require(encoded.length % 2 == 0) { "malformed fake-host command log" }
  return encoded
    .chunked(2)
    .map { octet -> octet.toInt(16).toByte() }
    .toByteArray()
    .decodeToString()
}
