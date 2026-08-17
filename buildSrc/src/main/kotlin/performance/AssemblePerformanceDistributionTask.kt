/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import performance.distribution.DistributionValidation
import performance.distribution.DistributionValidationRequest
import performance.distribution.DistributionValidator
import performance.distribution.JavaRuntimeIdentity
import performance.hash.Sha256
import performance.json.CanonicalJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** Builds one immutable, classpath-preserving distribution or reuses a validated baseline harness. */
@DisableCachingByDefault(because = "Git cleanliness and immutable publication are validated at execution time")
abstract class AssemblePerformanceDistributionTask : DefaultTask() {
  @get:Internal abstract val captureRunnerSourceDirectory: DirectoryProperty

  @get:Optional @get:Input abstract val captureGitSha: Property<String>

  @get:Internal abstract val treatmentSourceDirectory: DirectoryProperty

  @get:Optional @get:Input abstract val treatmentGitSha: Property<String>

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val treatmentJar: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val benchmarkJar: RegularFileProperty

  @get:Classpath abstract val benchmarkDependencies: ConfigurableFileCollection

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val runnerDistributionDirectory: DirectoryProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val protocolSchemaDirectory: DirectoryProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val profileDirectory: DirectoryProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val runtimeDirectory: DirectoryProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val qualificationPolicyDirectory: DirectoryProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val testVectorDirectory: DirectoryProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val expectedCells: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val adapter: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val embeddedDependency: RegularFileProperty

  @get:Input abstract val embeddedDependencyCoordinate: Property<String>

  @get:Input abstract val expectedBenchmarks: ListProperty<String>

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val protocolClosureManifest: RegularFileProperty

  @get:Optional
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val harnessFrom: DirectoryProperty

  @get:Internal abstract val distributionDirectory: DirectoryProperty

  @TaskAction
  fun assemble() {
    val captureRoot = captureRunnerSourceDirectory.get().asFile.toPath()
    val treatmentRoot = treatmentSourceDirectory.get().asFile.toPath()
    val captureSha = sourceSha(captureRoot, captureGitSha.orNull)
    val treatmentSha = sourceSha(treatmentRoot, treatmentGitSha.orNull)
    val output = distributionDirectory.get().asFile.toPath().toAbsolutePath().normalize()
    require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
      "distribution output must not already exist"
    }
    Files.createDirectories(output.parent)
    val staging = Files.createTempDirectory(output.parent, ".${output.fileName}.assembling.")

    try {
      if (harnessFrom.isPresent) {
        assembleCandidate(staging, harnessFrom.get().asFile.toPath(), treatmentSha, captureSha)
      } else {
        assembleInitial(staging, treatmentSha, captureSha)
      }
      writeChecksums(staging)
      requireValid(staging, output, "assembled distribution is invalid")
      if (harnessFrom.isPresent) {
        CandidateDistributionDiff.requireAllowed(harnessFrom.get().asFile.toPath(), staging)
      }
      publish(staging, output)
    } catch (failure: Exception) {
      deleteTree(staging)
      throw failure
    }
  }

  private fun assembleInitial(staging: Path, treatmentSha: String, captureSha: String) {
    copyRegular(treatmentJar.get().asFile.toPath(), staging.resolve(PRODUCTION_JAR))
    copyRegular(benchmarkJar.get().asFile.toPath(), staging.resolve(BENCHMARK_JAR))
    val benchmarkLibraries = copyBenchmarkLibraries(staging)
    val runnerLibraries = copyRunner(staging)
    val runnerClasspath = listOf(RUNNER_JAR) + runnerLibraries
    writeLaunchers(staging, runnerClasspath)
    copyProtocol(staging)
    writeClasspath(staging, benchmarkLibraries, runnerClasspath)
    writeProvenance(
      staging = staging,
      treatmentSha = treatmentSha,
      immutableHarnessSha = captureSha,
      freezerSha = captureSha,
    )
    writeProtocol(staging)
  }

  private fun assembleCandidate(
    staging: Path,
    baseline: Path,
    treatmentSha: String,
    freezerSha: String,
  ) {
    val normalizedBaseline = baseline.toAbsolutePath().normalize()
    requireValid(normalizedBaseline, null, "baseline distribution is invalid")
    val baselineProtocol = jsonObject(normalizedBaseline.resolve(PROTOCOL_MANIFEST))
    val currentClosure = jsonObject(protocolClosureManifest.get().asFile.toPath())
    require(
      baselineProtocol.get("sourceClosure") == currentClosure.get("sourceClosure") &&
        baselineProtocol.get("toolIdentities") == currentClosure.get("toolIdentities")
    ) {
      "baseline distribution protocol does not match the clean current runner"
    }

    copyTree(normalizedBaseline, staging)
    copyRegular(treatmentJar.get().asFile.toPath(), staging.resolve(PRODUCTION_JAR), replace = true)
    val classpath = jsonObject(staging.resolve(CLASSPATH_MANIFEST))
    classpath.array("benchmarkClasspath").values().asSequence().map(JsonNode::asObject).single {
        it.text("path") == PRODUCTION_JAR
      }
      .apply {
        put("byteLength", Files.size(staging.resolve(PRODUCTION_JAR)))
        put("sha256", sha(staging.resolve(PRODUCTION_JAR)).hex)
      }
    writeJson(staging.resolve(CLASSPATH_MANIFEST), classpath)

    val baselineProvenance = jsonObject(normalizedBaseline.resolve(PROVENANCE_MANIFEST))
    val immutableHarnessSha =
      baselineProvenance.objectNode("immutableHarness").text("gitSha")
    writeProvenance(staging, treatmentSha, immutableHarnessSha, freezerSha)
  }

  private fun copyBenchmarkLibraries(staging: Path): List<String> {
    val embedded = embeddedDependency.get().asFile.toPath().toAbsolutePath().normalize()
    val dependencies =
      benchmarkDependencies.files
        .map { it.toPath().toAbsolutePath().normalize() }
        .filterNot { it == embedded }
    require(dependencies.all { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) {
      "benchmark runtime contains a non-jar entry"
    }
    val names = dependencies.map(Path::getFileName).map(Path::toString)
    require(names.distinct().size == names.size) { "benchmark dependency filenames must be unique" }
    return dependencies.map { source ->
      val relative = "lib/${source.fileName}"
      copyRegular(source, staging.resolve(relative))
      relative
    }
  }

  private fun copyRunner(staging: Path): List<String> {
    val sourceRoot = runnerDistributionDirectory.get().asFile.toPath().toAbsolutePath().normalize()
    require(Files.isDirectory(sourceRoot.resolve("bin"), LinkOption.NOFOLLOW_LINKS)) {
      "runner installDist bin directory is missing"
    }
    val libraries = regularFiles(sourceRoot.resolve("lib"))
    val runner = libraries.singleOrNull { it.fileName.toString() == "performance-runner.jar" }
      ?: throw GradleException("runner installDist must contain exactly performance-runner.jar")
    copyRegular(runner, staging.resolve(RUNNER_JAR))
    return libraries.filterNot { it == runner }.map { source ->
      val relative = "runner/lib/${source.fileName}"
      copyRegular(source, staging.resolve(relative))
      relative
    }
  }

  private fun writeLaunchers(staging: Path, runnerClasspath: List<String>) {
    val declaredClasspath = runnerClasspath.joinToString(":")
    val unix =
      """
      #!/bin/sh
      set -eu
      root=${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")/.." && pwd -P)
      # frozen-classpath=$declaredClasspath
      exec /opt/java/openjdk/bin/java -cp "${runnerClasspath.joinToString(":") { "${'$'}root/$it" }}" performance.cli.PerformanceRunnerMainKt "${'$'}@"
      """.trimIndent() + "\n"
    val unixPath = staging.resolve(UNIX_LAUNCHER)
    write(unixPath, unix.encodeToByteArray())
    Files.setPosixFilePermissions(
      unixPath,
      setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_EXECUTE,
      ),
    )
    val windowsClasspath = runnerClasspath.joinToString(";") { "%ROOT%\\${it.replace('/', '\\')}" }
    write(
      staging.resolve(WINDOWS_LAUNCHER),
      "@echo off\r\nset ROOT=%~dp0..\r\nrem frozen-classpath=${runnerClasspath.joinToString(":")}\r\n\"%JAVA_HOME%\\bin\\java.exe\" -cp \"$windowsClasspath\" performance.cli.PerformanceRunnerMainKt %*\r\n"
        .encodeToByteArray(),
    )
  }

  private fun copyProtocol(staging: Path) {
    copyTree(protocolSchemaDirectory.get().asFile.toPath(), staging.resolve("protocol/schemas"))
    copyTree(profileDirectory.get().asFile.toPath(), staging.resolve("protocol/profiles"))
    copyTree(runtimeDirectory.get().asFile.toPath(), staging.resolve("protocol/runtime"))
    copyTree(
      qualificationPolicyDirectory.get().asFile.toPath(),
      staging.resolve("protocol/qualification"),
    )
    copyTree(testVectorDirectory.get().asFile.toPath(), staging.resolve("protocol/test-vectors"))
    copyRegular(expectedCells.get().asFile.toPath(), staging.resolve("protocol/expected-cells.json"))
    copyRegular(adapter.get().asFile.toPath(), staging.resolve("protocol/adapter/run"))
  }

  private fun writeClasspath(
    staging: Path,
    benchmarkLibraries: List<String>,
    runnerClasspath: List<String>,
  ) {
    val java = currentJava()
    val benchmarkClasspath = listOf(BENCHMARK_JAR, PRODUCTION_JAR) + benchmarkLibraries
    val document =
      JsonNodeFactory.instance.objectNode().apply {
        put("schemaVersion", "distribution-classpath-v1")
        set(
          "javaRuntime",
          JsonNodeFactory.instance.objectNode().apply {
            put("executable", java.executable.toString())
            put("executableSha256", java.sha256.hex)
            put("featureVersion", java.featureVersion)
          },
        )
        set(
          "benchmarkClasspath",
          classpathEntries(staging, benchmarkClasspath) { path ->
            when (path) {
              BENCHMARK_JAR -> "com.salesforce.revoman:benchmarks"
              PRODUCTION_JAR -> "com.salesforce.revoman:revoman"
              else -> coordinate("resolved", Path.of(path).fileName.toString())
            }
          },
        )
        set(
          "runnerClasspath",
          classpathEntries(staging, runnerClasspath) { path ->
            if (path == RUNNER_JAR) {
              "com.salesforce.revoman:performance-runner"
            } else {
              coordinate("runner", Path.of(path).fileName.toString())
            }
          },
        )
        set(
          "embeddedDependencies",
          JsonNodeFactory.instance.arrayNode().apply {
            add(
              JsonNodeFactory.instance.objectNode().apply {
                put("coordinate", embeddedDependencyCoordinate.get())
                put("placement", "embedded:app/revoman.jar")
                put("sha256", sha(embeddedDependency.get().asFile.toPath()).hex)
              },
            )
          },
        )
        set(
          "expectedBenchmarks",
          JsonNodeFactory.instance.arrayNode().apply {
            expectedBenchmarks.get().sorted().forEach(::add)
          },
        )
      }
    writeJson(staging.resolve(CLASSPATH_MANIFEST), document)
  }

  private fun classpathEntries(
    staging: Path,
    paths: List<String>,
    coordinate: (String) -> String,
  ): ArrayNode =
    JsonNodeFactory.instance.arrayNode().apply {
      paths.forEachIndexed { index, relative ->
        val file = staging.resolve(relative)
        add(
          JsonNodeFactory.instance.objectNode().apply {
            put("byteLength", Files.size(file))
            put("coordinate", coordinate(relative))
            put("order", index)
            put("path", relative)
            put("sha256", sha(file).hex)
          },
        )
      }
    }

  private fun writeProvenance(
    staging: Path,
    treatmentSha: String,
    immutableHarnessSha: String,
    freezerSha: String,
  ) {
    val document =
      JsonNodeFactory.instance.objectNode().apply {
        put("schemaVersion", "distribution-provenance-v1")
        set("treatment", gitIdentity(treatmentSha))
        set("immutableHarness", gitIdentity(immutableHarnessSha))
        set("distributionFreezer", gitIdentity(freezerSha))
      }
    writeJson(staging.resolve(PROVENANCE_MANIFEST), document)
  }

  private fun writeProtocol(staging: Path) {
    val closure = jsonObject(protocolClosureManifest.get().asFile.toPath())
    val launchers = listOf(UNIX_LAUNCHER, WINDOWS_LAUNCHER)
    val schemas = protocolFiles(staging, "protocol/schemas")
    val profiles = protocolFiles(staging, "protocol/profiles")
    val runtimes = protocolFiles(staging, "protocol/runtime")
    val policies = protocolFiles(staging, "protocol/qualification")
    val vectors = protocolFiles(staging, "protocol/test-vectors")
    val document =
      JsonNodeFactory.instance.objectNode().apply {
        put("schemaVersion", "distribution-protocol-v1")
        put("protocolSha256", "0".repeat(64))
        set("runner", artifact(staging, RUNNER_JAR))
        set("adapter", artifact(staging, "protocol/adapter/run"))
        set("launchers", artifacts(staging, launchers))
        set("schemas", artifacts(staging, schemas))
        set("profiles", artifacts(staging, profiles))
        set("runtimeDeclarations", artifacts(staging, runtimes))
        set("qualificationPolicies", artifacts(staging, policies))
        set("expectedCells", artifact(staging, "protocol/expected-cells.json"))
        set("sourceClosure", closure.array("sourceClosure").deepCopy())
        set("testVectors", artifacts(staging, vectors))
        set(
          "toolIdentities",
          closure.objectNode("toolIdentities").deepCopy(),
        )
      }
    document.put("protocolSha256", protocolHash(document).hex)
    writeJson(staging.resolve(PROTOCOL_MANIFEST), document)
  }

  private fun protocolHash(protocol: ObjectNode): Sha256 {
    val artifactFields =
      listOf(
        "runner",
        "adapter",
        "launchers",
        "schemas",
        "profiles",
        "runtimeDeclarations",
        "qualificationPolicies",
        "expectedCells",
        "testVectors",
      )
    val lines = mutableListOf<String>()
    artifactFields.forEach { field ->
      when (val value = protocol.get(field)) {
        is ArrayNode -> value.values().asSequence().map(JsonNode::asObject).forEach { binding ->
          lines += "artifact\t${binding.text("sha256")}\t${binding.text("path")}"
        }
        else ->
          value.asObject().let { binding ->
            lines += "artifact\t${binding.text("sha256")}\t${binding.text("path")}"
          }
      }
    }
    protocol.array("sourceClosure").values().asSequence().map(JsonNode::asObject).forEach { binding ->
      lines += "source\t${binding.text("sha256")}\t${binding.text("path")}"
    }
    protocol.objectNode("toolIdentities").properties().forEach { (key, value) ->
      lines += "identity\t$key\t${value.asString()}"
    }
    return Sha256.digest(lines.sorted().joinToString("\n", postfix = "\n").encodeToByteArray())
  }

  private fun artifacts(staging: Path, paths: List<String>): ArrayNode =
    JsonNodeFactory.instance.arrayNode().apply {
      paths.sorted().forEach { add(artifact(staging, it)) }
    }

  private fun artifact(staging: Path, relative: String): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("path", relative)
      put("sha256", sha(staging.resolve(relative)).hex)
    }

  private fun protocolFiles(staging: Path, relativeDirectory: String): List<String> {
    val root = staging.resolve(relativeDirectory)
    return regularFiles(root).map { "$relativeDirectory/${portable(root.relativize(it))}" }
  }

  private fun writeChecksums(staging: Path) {
    val checksum = staging.resolve(CHECKSUM_MANIFEST)
    Files.deleteIfExists(checksum)
    val lines =
      regularFiles(staging)
        .map { file -> portable(staging.relativize(file)) to sha(file) }
        .filterNot { (path) -> path == CHECKSUM_MANIFEST }
        .sortedBy(Pair<String, Sha256>::first)
        .joinToString(separator = "\n", postfix = "\n") { (path, digest) ->
          "${digest.hex}  $path"
        }
    write(checksum, lines.encodeToByteArray())
  }

  private fun requireValid(root: Path, stagingOutput: Path?, message: String) {
    val result =
      DistributionValidator()
        .validate(
          DistributionValidationRequest(
            root = root,
            selectedJava = currentJava(),
            stagingOutput = stagingOutput,
          ),
        )
    if (result is DistributionValidation.Invalid) {
      throw GradleException("$message: ${result.problems.joinToString(",")}")
    }
  }

  private fun requireCleanGit(root: Path): String {
    val normalized = root.toAbsolutePath().normalize()
    val status = git(normalized, "status", "--porcelain=v1", "--untracked-files=all")
    require(status.isEmpty()) { "source tree must be clean" }
    val sha = git(normalized, "rev-parse", "HEAD")
    require(sha.matches(Regex("[0-9a-f]{40}"))) { "source Git identity is invalid" }
    return sha
  }

  private fun sourceSha(root: Path, externallyVerified: String?): String {
    if (externallyVerified == null) {
      return requireCleanGit(root)
    }
    require(externallyVerified.matches(Regex("[0-9a-f]{40}"))) {
      "source Git identity is invalid"
    }
    return externallyVerified
  }

  private fun git(root: Path, vararg arguments: String): String {
    val process =
      ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8).trim()
    require(process.waitFor() == 0) { "source Git operation failed" }
    return output
  }

  private fun currentJava(): JavaRuntimeIdentity {
    val executable =
      Path.of(
          checkNotNull(ProcessHandle.current().info().command().orElse(null)) {
            "current Java executable is unavailable"
          },
        )
        .toAbsolutePath()
        .normalize()
    return JavaRuntimeIdentity(
      executable = executable,
      featureVersion = Runtime.version().feature(),
      sha256 = sha(executable),
    )
  }

  private fun copyTree(source: Path, destination: Path) {
    val normalized = source.toAbsolutePath().normalize()
    require(Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(normalized)) {
      "distribution input directory is invalid"
    }
    Files.walk(normalized).use { paths ->
      paths.forEach { path ->
        require(!Files.isSymbolicLink(path)) { "distribution input cannot contain symbolic links" }
        val target = destination.resolve(normalized.relativize(path).toString())
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          Files.createDirectories(target)
        } else {
          copyRegular(path, target)
        }
      }
    }
  }

  private fun copyRegular(source: Path, destination: Path, replace: Boolean = false) {
    require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source)) {
      "distribution input must be a regular file"
    }
    Files.createDirectories(destination.parent)
    val options =
      if (replace) {
        arrayOf(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
      } else {
        arrayOf(StandardCopyOption.COPY_ATTRIBUTES)
      }
    Files.copy(source, destination, *options)
  }

  private fun regularFiles(root: Path): List<Path> =
    Files.walk(root).use { paths ->
      val all = paths.toList()
      require(all.none(Files::isSymbolicLink)) { "distribution input cannot contain symbolic links" }
      all
        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        .sortedBy { portable(root.relativize(it)) }
    }

  private fun publish(staging: Path, output: Path) {
    try {
      Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE)
    } catch (unsupported: AtomicMoveNotSupportedException) {
      throw GradleException("distribution requires an atomic same-filesystem move", unsupported)
    }
  }

  private fun deleteTree(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(root).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }

  private fun write(path: Path, bytes: ByteArray) {
    Files.createDirectories(path.parent)
    Files.write(path, bytes)
  }

  private fun writeJson(path: Path, value: JsonNode) = write(path, CanonicalJson.encode(value))

  private fun jsonObject(path: Path): ObjectNode =
    CanonicalJson.parseStrict(Files.readAllBytes(path)) as ObjectNode

  private fun gitIdentity(sha: String): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("gitSha", sha)
      put("treeClean", true)
    }

  private fun coordinate(group: String, fileName: String): String {
    val artifact =
      fileName
        .removeSuffix(".jar")
        .replace(Regex("[^A-Za-z0-9_.-]"), "-")
        .ifBlank { "artifact" }
    return "$group:$artifact"
  }

  private fun sha(path: Path): Sha256 = Sha256.digest(Files.readAllBytes(path))

  private fun ObjectNode.array(name: String): ArrayNode = get(name) as ArrayNode

  private fun ObjectNode.objectNode(name: String): ObjectNode = get(name) as ObjectNode

  private fun ObjectNode.text(name: String): String = get(name).asString()

  private companion object {
    const val PRODUCTION_JAR = "app/revoman.jar"
    const val BENCHMARK_JAR = "benchmark/revoman-jmh.jar"
    const val UNIX_LAUNCHER = "bin/performance-runner"
    const val WINDOWS_LAUNCHER = "bin/performance-runner.bat"
    const val RUNNER_JAR = "runner/performance-runner.jar"
    const val CLASSPATH_MANIFEST = "metadata/classpath.json"
    const val PROVENANCE_MANIFEST = "metadata/provenance.json"
    const val PROTOCOL_MANIFEST = "metadata/protocol.json"
    const val CHECKSUM_MANIFEST = "metadata/distribution.sha256"
  }
}

/** Enforces the candidate contract at field level after both distributions validate independently. */
internal object CandidateDistributionDiff {
  fun requireAllowed(baseline: Path, candidate: Path) {
    val baselineFiles = relativeFiles(baseline)
    val candidateFiles = relativeFiles(candidate)
    require(baselineFiles.keys == candidateFiles.keys) {
      "candidate distribution changed the frozen harness layout"
    }
    val changed =
      baselineFiles.keys.filter { relative ->
        !Files.readAllBytes(baselineFiles.getValue(relative))
          .contentEquals(Files.readAllBytes(candidateFiles.getValue(relative)))
      }
    require(changed.all(ALLOWED_FILE_DIFFS::contains)) {
      "candidate distribution changed a non-allowlisted harness artifact"
    }
    requireAllowedClasspathFields(baseline, candidate)
    requireAllowedProvenanceFields(baseline, candidate)
    requireAllowedChecksumEntries(baseline, candidate)
  }

  private fun requireAllowedClasspathFields(baseline: Path, candidate: Path) {
    val baselineDocument = jsonObject(baseline.resolve(CLASSPATH_MANIFEST))
    val normalizedCandidate: ObjectNode =
      jsonObject(candidate.resolve(CLASSPATH_MANIFEST)).deepCopy()
    val baselineApplication = applicationEntry(baselineDocument)
    val candidateApplication = applicationEntry(normalizedCandidate)
    candidateApplication.put("byteLength", baselineApplication.get("byteLength").asLong())
    candidateApplication.put("sha256", baselineApplication.get("sha256").asString())
    require(canonicalBytesEqual(normalizedCandidate, baselineDocument)) {
      "candidate distribution changed non-derived classpath fields"
    }
  }

  private fun requireAllowedProvenanceFields(baseline: Path, candidate: Path) {
    val baselineDocument = jsonObject(baseline.resolve(PROVENANCE_MANIFEST))
    val normalizedCandidate: ObjectNode =
      jsonObject(candidate.resolve(PROVENANCE_MANIFEST)).deepCopy()
    normalizedCandidate.set("treatment", baselineDocument.get("treatment").deepCopy())
    normalizedCandidate.set(
      "distributionFreezer",
      baselineDocument.get("distributionFreezer").deepCopy(),
    )
    require(canonicalBytesEqual(normalizedCandidate, baselineDocument)) {
      "candidate distribution changed non-derived provenance fields"
    }
  }

  private fun requireAllowedChecksumEntries(baseline: Path, candidate: Path) {
    val baselineEntries = checksumEntries(baseline)
    val candidateEntries = checksumEntries(candidate)
    require(baselineEntries.keys.toList() == candidateEntries.keys.toList()) {
      "candidate distribution changed checksum paths"
    }
    require(
      baselineEntries.keys.all { path ->
        path in ALLOWED_CHECKSUM_ENTRY_DIFFS ||
          baselineEntries.getValue(path) == candidateEntries.getValue(path)
      },
    ) {
      "candidate distribution changed a non-derived checksum entry"
    }
  }

  private fun applicationEntry(document: ObjectNode): ObjectNode =
    (document.get("benchmarkClasspath") as ArrayNode)
      .values()
      .asSequence()
      .map(JsonNode::asObject)
      .single { entry -> entry.get("path").asString() == PRODUCTION_JAR }

  private fun checksumEntries(root: Path): LinkedHashMap<String, String> =
    Files.readAllLines(root.resolve(CHECKSUM_MANIFEST), StandardCharsets.UTF_8)
      .filter(String::isNotEmpty)
      .associateTo(linkedMapOf()) { line ->
        val match = checkNotNull(CHECKSUM_LINE.matchEntire(line))
        match.groupValues[2] to match.groupValues[1]
      }

  private fun relativeFiles(root: Path): Map<String, Path> =
    Files.walk(root).use { paths ->
      paths
        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        .sorted(compareBy { portable(root.relativize(it)) })
        .toList()
        .associateBy { portable(root.relativize(it)) }
    }

  private fun jsonObject(path: Path): ObjectNode =
    CanonicalJson.parseStrict(Files.readAllBytes(path)) as ObjectNode

  private fun canonicalBytesEqual(left: JsonNode, right: JsonNode): Boolean =
    CanonicalJson.encode(left).contentEquals(CanonicalJson.encode(right))

  private const val PRODUCTION_JAR = "app/revoman.jar"
  private const val CLASSPATH_MANIFEST = "metadata/classpath.json"
  private const val PROVENANCE_MANIFEST = "metadata/provenance.json"
  private const val CHECKSUM_MANIFEST = "metadata/distribution.sha256"
  private val CHECKSUM_LINE = Regex("([0-9a-f]{64})  ([^\\r\\n]+)")
  private val ALLOWED_FILE_DIFFS =
    setOf(
      PRODUCTION_JAR,
      CLASSPATH_MANIFEST,
      PROVENANCE_MANIFEST,
      CHECKSUM_MANIFEST,
    )
  private val ALLOWED_CHECKSUM_ENTRY_DIFFS =
    setOf(
      PRODUCTION_JAR,
      CLASSPATH_MANIFEST,
      PROVENANCE_MANIFEST,
    )
}
