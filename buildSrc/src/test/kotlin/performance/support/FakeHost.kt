/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.support

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

  init {
    artifactRoot.createDirectories()
    reviewedArtifactRoot.createDirectories()
    repositoryRoot.resolve("inputs").createDirectories()
    repositoryRoot.resolve("scripts/performance").createDirectories()
    repositoryRoot.resolve("config/performance/runtime").createDirectories()
    copyProtocolFiles()
    installFakeCommands()
  }

  fun treatmentSource(name: String = "treatment"): Path =
    repositoryRoot.resolve("inputs/$name").also { directory -> directory.createDirectories() }

  fun frozenDistribution(
    name: String,
    matchingAdapter: Boolean = true,
  ): Path =
    repositoryRoot.resolve("inputs/$name").also { distribution ->
      distribution.resolve("adapter").createDirectories()
      if (matchingAdapter && script.exists()) {
        Files.copy(script, distribution.resolve("adapter/run"), StandardCopyOption.REPLACE_EXISTING)
      } else {
        distribution.resolve("adapter/run").writeText("mismatched adapter\n")
      }
    }

  fun inputDirectory(name: String): Path =
    repositoryRoot.resolve("inputs/$name").also { directory -> directory.createDirectories() }

  fun output(token: String): String = "build/performance/$token"

  fun outputPath(token: String): Path = artifactRoot.resolve(token)

  fun invoke(
    vararg arguments: String,
    environment: Map<String, String> = emptyMap(),
    functionOverrides: String? = null,
  ): AdapterInvocation {
    Files.deleteIfExists(commandLog)
    val command =
      functionOverrides?.let { overrides ->
        listOf(
          "/bin/bash",
          "-c",
          "source \"\$1\"; shift; $overrides; main \"\$@\"",
          "adapter-contract-test",
          script.toString(),
          *arguments,
        )
      } ?: listOf("/bin/bash", script.toString(), *arguments)

    return run(command, environment)
  }

  fun invokePackaged(vararg arguments: String): AdapterInvocation =
    run(listOf(sourceRoot.resolve("scripts/performance/run").toString(), *arguments), emptyMap())

  private fun run(
    command: List<String>,
    environment: Map<String, String>,
  ): AdapterInvocation {
    val standardOutput = Files.createTempFile(repositoryRoot, "stdout.", ".log")
    val standardError = Files.createTempFile(repositoryRoot, "stderr.", ".log")
    val process =
      ProcessBuilder(command)
        .directory(repositoryRoot.toFile())
        .redirectOutput(standardOutput.toFile())
        .redirectError(standardError.toFile())
    process.environment().apply {
      put("PATH", "$fakeBin:/usr/bin:/bin:/usr/sbin:/sbin")
      put("FAKE_HOST_LOG", commandLog.toString())
      put("FAKE_REPO_ROOT", repositoryRoot.toString())
      put("FAKE_GIT_SHA", "0123456789abcdef0123456789abcdef01234567")
      put("FAKE_GIT_STATUS", "")
      putAll(environment)
    }

    val exitCode = process.start().waitFor()
    return AdapterInvocation(
      exitCode = exitCode,
      standardOutput = standardOutput.readText(),
      standardError = standardError.readText(),
      commands =
        commandLog.takeIf(Path::exists)?.readLines()?.map { line -> line.split('\t') }.orEmpty(),
    )
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
  }

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
    private val FAKE_COMMANDS =
      listOf("docker", "git", "java", "gradle", "sudo", "dzdo", "osascript")
  }
}
