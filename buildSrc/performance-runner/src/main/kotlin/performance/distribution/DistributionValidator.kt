/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.distribution

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import performance.hash.Sha256

/** Stable, privacy-safe distribution failures. Values never contain rejected paths or bytes. */
enum class DistributionProblem {
  INVALID_LAYOUT,
  SYMBOLIC_LINK_NOT_ALLOWED,
  CHECKSUM_MANIFEST_MISSING,
  CHECKSUM_MANIFEST_INVALID,
  CHECKSUM_ENTRY_MISSING,
  CHECKSUM_ENTRY_TARGET_MISSING,
  CHECKSUM_MISMATCH,
  METADATA_MISSING,
  METADATA_INVALID_JSON,
  METADATA_NOT_CANONICAL,
  METADATA_SCHEMA_INVALID,
  INVALID_CLASSPATH_ENTRY,
  DUPLICATE_CLASSPATH_ENTRY,
  INVALID_CLASSPATH_ORDER,
  CLASSPATH_ENTRY_MISSING,
  CLASSPATH_HASH_MISMATCH,
  CLASSPATH_SIZE_MISMATCH,
  TEST_DEPENDENCY_PRESENT,
  TEST_CONTENT_PRESENT,
  INVALID_JAR,
  INVALID_MULTI_RELEASE_JAR,
  DUPLICATE_EFFECTIVE_CLASS,
  INVALID_SERVICE_DESCRIPTOR,
  SERVICE_PROVIDER_MISSING,
  BENCHMARK_METADATA_MISSING,
  INVALID_BENCHMARK_METADATA,
  UNEXPECTED_BENCHMARK,
  JAVA_VERSION_UNSUPPORTED,
  JAVA_RUNTIME_MISMATCH,
  STAGING_OUTPUT_NOT_NEW,
  PROTOCOL_LAYOUT_INVALID,
  PROTOCOL_HASH_MISMATCH,
  RUNNER_HASH_MISMATCH,
  ADAPTER_HASH_MISMATCH,
  SCHEMA_HASH_MISMATCH,
  PROFILE_HASH_MISMATCH,
  RUNTIME_HASH_MISMATCH,
  POLICY_HASH_MISMATCH,
  EXPECTED_CELLS_HASH_MISMATCH,
  TEST_VECTOR_HASH_MISMATCH,
  EMBEDDED_SCHEMA_MISMATCH,
  INTERNAL_VALIDATION_FAILURE,
}

/** Inputs needed to prove one frozen distribution before any process may be requested. */
data class DistributionValidationRequest(
  val root: Path,
  val selectedJava: JavaRuntimeIdentity,
  val expectedProtocolHash: Sha256? = null,
  val stagingOutput: Path? = null,
)

/** Either an unforgeable validated proof or a stable ordered set of privacy-safe failures. */
sealed interface DistributionValidation {
  /** A complete distribution proof that downstream runner operations may consume. */
  data class Valid(val distribution: VerifiedDistribution) : DistributionValidation

  /** Stable validation problems suitable for build-logic diagnostics. */
  data class Invalid(val problems: List<DistributionProblem>) : DistributionValidation
}

/** Validates the complete frozen layout and is the sole creator of distribution proofs. */
class DistributionValidator {
  /** Validates [request] without leaking rejected paths or throwing validation failures. */
  fun validate(request: DistributionValidationRequest): DistributionValidation =
    runCatching { validateSafely(request) }
      .getOrElse {
        invalid(DistributionProblem.INTERNAL_VALIDATION_FAILURE)
      }

  private fun validateSafely(request: DistributionValidationRequest): DistributionValidation {
    if (
      request.stagingOutput?.let { output ->
        Files.exists(output, LinkOption.NOFOLLOW_LINKS)
      } == true
    ) {
      return invalid(DistributionProblem.STAGING_OUTPUT_NOT_NEW)
    }

    val files =
      when (val layout = DistributionLayout.inspect(request.root)) {
        is DistributionLayoutInspection.Valid -> layout.files
        is DistributionLayoutInspection.Invalid -> return invalid(layout.problem)
      }
    when (val checksums = DistributionChecksumValidator.validate(files)) {
      DistributionChecksumValidation.Valid -> Unit
      is DistributionChecksumValidation.Invalid -> return invalid(checksums.problems)
    }

    val metadata =
      when (val manifests = DistributionManifestReader().read(files.root)) {
        is DistributionManifestRead.Valid -> manifests.metadata
        is DistributionManifestRead.Invalid -> return invalid(manifests.problems)
      }
    val problems = mutableListOf<DistributionProblem>()
    problems += validateJava(request.selectedJava, metadata.classpath.javaRuntime)
    val canonicalProtocolHash = protocolClosureHash(metadata.protocol)
    if (
      metadata.protocol.protocolSha256 != canonicalProtocolHash ||
        request.expectedProtocolHash?.let { it != canonicalProtocolHash } == true
    ) {
      problems += DistributionProblem.PROTOCOL_HASH_MISMATCH
    }
    problems += validateProtocol(files, metadata.protocol)

    val benchmark =
      validateClasspath(
        files = files,
        entries = metadata.classpath.benchmarkClasspath,
        featureVersion = request.selectedJava.featureVersion,
      )
    val runner =
      validateClasspath(
        files = files,
        entries = metadata.classpath.runnerClasspath,
        featureVersion = request.selectedJava.featureVersion,
      )
    problems += benchmark.problems
    problems += runner.problems
    problems += validateClasspathClosure(files, metadata.classpath)
    problems +=
      validateBenchmarkMetadata(
        metadata = metadata.classpath,
        entries = metadata.classpath.benchmarkClasspath,
        inspections = benchmark.inspections,
      )

    if (problems.isNotEmpty()) {
      return invalid(problems)
    }
    return DistributionValidation.Valid(
      ValidatedDistribution(
        root = files.root,
        metadata = metadata,
        benchmarkClasspath = immutableList(benchmark.paths),
        runnerClasspath = immutableList(runner.paths),
      ),
    )
  }

  private fun validateJava(
    selected: JavaRuntimeIdentity,
    declared: DeclaredJavaRuntime,
  ): List<DistributionProblem> {
    val problems = mutableListOf<DistributionProblem>()
    val currentFeature = Runtime.version().feature()
    if (
      currentFeature < MINIMUM_JAVA_FEATURE ||
        selected.featureVersion < MINIMUM_JAVA_FEATURE ||
        declared.featureVersion < MINIMUM_JAVA_FEATURE
    ) {
      problems += DistributionProblem.JAVA_VERSION_UNSUPPORTED
    }
    val selectedExecutable = selected.executable.toAbsolutePath().normalize()
    val declaredExecutable = declared.executable.toAbsolutePath().normalize()
    val currentExecutable =
      ProcessHandle.current().info().command().orElse(null)?.let { command ->
        Path.of(command).toAbsolutePath().normalize()
      }
    val actualDigest =
      runCatching {
          selectedExecutable
            .takeIf { path ->
              Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path) &&
                Files.isExecutable(path)
            }
            ?.let(::digest)
        }
        .getOrNull()
    if (
      !selected.executable.isAbsolute ||
        !declared.executable.isAbsolute ||
        selectedExecutable != declaredExecutable ||
        selectedExecutable != currentExecutable ||
        selected.featureVersion != currentFeature ||
        declared.featureVersion != currentFeature ||
        selected.sha256 != declared.executableSha256 ||
        actualDigest != selected.sha256
    ) {
      problems += DistributionProblem.JAVA_RUNTIME_MISMATCH
    }
    return immutableList(problems.distinct())
  }

  private fun validateClasspath(
    files: DistributionFiles,
    entries: List<DistributionClasspathEntry>,
    featureVersion: Int,
  ): ClasspathValidation {
    val problems = mutableListOf<DistributionProblem>()
    if (
      entries.map(DistributionClasspathEntry::path).distinct().size != entries.size ||
        entries.map(DistributionClasspathEntry::coordinate).distinct().size != entries.size
    ) {
      problems += DistributionProblem.DUPLICATE_CLASSPATH_ENTRY
    }
    if (entries.withIndex().any { (index, entry) -> entry.order != index }) {
      problems += DistributionProblem.INVALID_CLASSPATH_ORDER
    }
    if (entries.any(::isTestDependency)) {
      problems += DistributionProblem.TEST_DEPENDENCY_PRESENT
    }

    val validated =
      entries.mapNotNull { entry ->
        if (!validClasspathEntry(entry.path)) {
          problems += DistributionProblem.INVALID_CLASSPATH_ENTRY
          return@mapNotNull null
        }
        val path = files.resolve(entry.path)
        if (
          !path.startsWith(files.root) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(path)
        ) {
          problems += DistributionProblem.CLASSPATH_ENTRY_MISSING
          return@mapNotNull null
        }
        if (digest(path) != entry.sha256) {
          problems += DistributionProblem.CLASSPATH_HASH_MISMATCH
        }
        if (Files.size(path) != entry.byteLength) {
          problems += DistributionProblem.CLASSPATH_SIZE_MISMATCH
        }
        path to
          JarValidator.inspect(
            path = path,
            featureVersion = featureVersion,
            projectBuilt = entry.path in PROJECT_BUILT_JARS,
          )
      }
    problems += EffectiveClasspath.validate(validated.map(Pair<Path, JarInspection>::second))
    return ClasspathValidation(
      paths = immutableList(validated.map(Pair<Path, JarInspection>::first)),
      inspections = immutableList(validated.map(Pair<Path, JarInspection>::second)),
      problems = immutableList(problems.distinct()),
    )
  }

  private fun validateClasspathClosure(
    files: DistributionFiles,
    metadata: DistributionClasspathManifest,
  ): List<DistributionProblem> {
    val benchmarkPaths = metadata.benchmarkClasspath.map(DistributionClasspathEntry::path).toSet()
    val runnerPaths = metadata.runnerClasspath.map(DistributionClasspathEntry::path).toSet()
    val expectedBenchmarkPaths =
      files.relativePaths
        .filter { path ->
          path == DistributionLayout.BENCHMARK_JAR ||
            path == DistributionLayout.PRODUCTION_JAR ||
            path.matches(BENCHMARK_LIBRARY)
        }
        .toSet()
    val expectedRunnerPaths =
      files.relativePaths
        .filter { path ->
          path == DistributionLayout.RUNNER_JAR || path.matches(RUNNER_LIBRARY)
        }
        .toSet()
    return if (
      benchmarkPaths != expectedBenchmarkPaths ||
        runnerPaths != expectedRunnerPaths ||
        metadata.expectedBenchmarks != metadata.expectedBenchmarks.sorted() ||
        metadata.embeddedDependencies
            .map(EmbeddedDependency::coordinate)
            .distinct()
            .size != metadata.embeddedDependencies.size
    ) {
      listOf(DistributionProblem.PROTOCOL_LAYOUT_INVALID)
    } else {
      emptyList()
    }
  }

  private fun validateBenchmarkMetadata(
    metadata: DistributionClasspathManifest,
    entries: List<DistributionClasspathEntry>,
    inspections: List<JarInspection>,
  ): List<DistributionProblem> {
    val benchmarkIndex = entries.indexOfFirst { it.path == DistributionLayout.BENCHMARK_JAR }
    if (benchmarkIndex !in inspections.indices) {
      return listOf(DistributionProblem.BENCHMARK_METADATA_MISSING)
    }
    val inspection = inspections[benchmarkIndex]
    val problems = mutableListOf<DistributionProblem>()
    if (inspection.benchmarkNames == null || !inspection.hasCompilerHints) {
      problems += DistributionProblem.BENCHMARK_METADATA_MISSING
    }
    if (
      inspection.benchmarkNames != null &&
        inspection.benchmarkNames != metadata.expectedBenchmarks.toSet()
    ) {
      problems += DistributionProblem.UNEXPECTED_BENCHMARK
    }
    return immutableList(problems)
  }

  private fun validateProtocol(
    files: DistributionFiles,
    protocol: DistributionProtocolManifest,
  ): List<DistributionProblem> {
    val problems = mutableListOf<DistributionProblem>()
    val categorized =
      listOf(
        protocol.runner to DistributionProblem.RUNNER_HASH_MISMATCH,
        protocol.adapter to DistributionProblem.ADAPTER_HASH_MISMATCH,
      ) +
        protocol.launchers.map { it to DistributionProblem.PROTOCOL_HASH_MISMATCH } +
        protocol.schemas.map { it to DistributionProblem.SCHEMA_HASH_MISMATCH } +
        protocol.profiles.map { it to DistributionProblem.PROFILE_HASH_MISMATCH } +
        protocol.runtimeDeclarations.map { it to DistributionProblem.RUNTIME_HASH_MISMATCH } +
        protocol.qualificationPolicies.map { it to DistributionProblem.POLICY_HASH_MISMATCH } +
        listOf(protocol.expectedCells to DistributionProblem.EXPECTED_CELLS_HASH_MISMATCH) +
        protocol.testVectors.map { it to DistributionProblem.TEST_VECTOR_HASH_MISMATCH }

    categorized.forEach { (binding, mismatch) ->
      if (!DistributionLayout.isNormalizedRelativePath(binding.path)) {
        problems += DistributionProblem.PROTOCOL_LAYOUT_INVALID
      } else {
        val path = files.resolve(binding.path)
        if (
          !path.startsWith(files.root) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(path) ||
            digest(path) != binding.sha256
        ) {
          problems += mismatch
        }
      }
    }
    if (protocol.bindings().map(DistributionArtifactBinding::path).distinct().size != protocol.bindings().size) {
      problems += DistributionProblem.PROTOCOL_LAYOUT_INVALID
    }
    if (!hasRequiredProtocolLayout(protocol)) {
      problems += DistributionProblem.PROTOCOL_LAYOUT_INVALID
    }
    if (
      files.relativePaths.filter(PROTOCOL_SCHEMA::matches).toSet() != REQUIRED_SCHEMAS
    ) {
      problems += DistributionProblem.PROTOCOL_LAYOUT_INVALID
    }
    if (!embeddedSchemasMatch(files, protocol.schemas)) {
      problems += DistributionProblem.EMBEDDED_SCHEMA_MISMATCH
    }
    return immutableList(problems.distinct())
  }

  private fun protocolClosureHash(protocol: DistributionProtocolManifest): Sha256 =
    Sha256.digest(
      buildList {
          addAll(
            protocol.bindings().map { binding ->
              "artifact\t${binding.sha256.hex}\t${binding.path}"
            },
          )
          addAll(
            protocol.sourceClosure.map { binding ->
              "source\t${binding.sha256.hex}\t${binding.path}"
            },
          )
          addAll(
            protocol.toolIdentities.map { (key, value) -> "identity\t$key\t$value" },
          )
        }
        .sorted()
        .joinToString(separator = "\n", postfix = "\n")
        .encodeToByteArray(),
    )

  private fun hasRequiredProtocolLayout(protocol: DistributionProtocolManifest): Boolean =
    protocol.runner.path == DistributionLayout.RUNNER_JAR &&
      protocol.adapter.path == "protocol/adapter/run" &&
      protocol.launchers.map(DistributionArtifactBinding::path).toSet() == REQUIRED_LAUNCHERS &&
      protocol.expectedCells.path == "protocol/expected-cells.json" &&
      protocol.schemas.map(DistributionArtifactBinding::path).toSet() == REQUIRED_SCHEMAS &&
      protocol.profiles.map(DistributionArtifactBinding::path).toSet() == REQUIRED_PROFILES &&
      protocol.runtimeDeclarations.map(DistributionArtifactBinding::path).toSet() ==
        REQUIRED_RUNTIMES &&
      protocol.qualificationPolicies.map(DistributionArtifactBinding::path).toSet() ==
        REQUIRED_POLICIES &&
      protocol.testVectors.map(DistributionArtifactBinding::path).toSet() == REQUIRED_TEST_VECTORS &&
      protocol.sourceClosure.isNotEmpty() &&
      protocol.sourceClosure.map(DistributionArtifactBinding::path).let { paths ->
        paths == paths.sorted() &&
          paths.distinct().size == paths.size &&
          paths.all(DistributionLayout::isNormalizedRelativePath)
      } &&
      protocol.toolIdentities.keys == REQUIRED_TOOL_IDENTITIES &&
      protocol.toolIdentities.values.none(String::isBlank) &&
      protocol.schemas.all { binding -> binding.path.matches(PROTOCOL_SCHEMA) } &&
      listOf(
          protocol.launchers,
          protocol.schemas,
          protocol.profiles,
          protocol.runtimeDeclarations,
          protocol.qualificationPolicies,
          protocol.testVectors,
        )
        .all { bindings ->
          val paths = bindings.map(DistributionArtifactBinding::path)
          paths == paths.sorted()
        }

  private fun embeddedSchemasMatch(
    files: DistributionFiles,
    schemas: List<DistributionArtifactBinding>,
  ): Boolean {
    val byPath = schemas.associateBy(DistributionArtifactBinding::path)
    return REQUIRED_SCHEMAS.all { relativePath ->
      val embeddedDigest =
        DistributionValidator::class.java
          .getResourceAsStream("/performance/$relativePath")
          ?.use { stream -> Sha256.digest(stream.readAllBytes()) }
      val distributedPath = files.resolve(relativePath)
      embeddedDigest != null &&
        Files.isRegularFile(distributedPath, LinkOption.NOFOLLOW_LINKS) &&
        digest(distributedPath) == embeddedDigest &&
        byPath[relativePath]?.sha256 == embeddedDigest
    }
  }

  private fun validClasspathEntry(relativePath: String): Boolean =
    DistributionLayout.isNormalizedRelativePath(relativePath) &&
      relativePath.endsWith(".jar") &&
      (relativePath == DistributionLayout.PRODUCTION_JAR ||
        relativePath == DistributionLayout.BENCHMARK_JAR ||
        relativePath == DistributionLayout.RUNNER_JAR ||
        relativePath.matches(BENCHMARK_LIBRARY) ||
        relativePath.matches(RUNNER_LIBRARY))

  private fun isTestDependency(entry: DistributionClasspathEntry): Boolean {
    val normalized = "${entry.coordinate}:${entry.path}".lowercase()
    return TEST_DEPENDENCY_MARKER.containsMatchIn(normalized)
  }

  private fun invalid(problem: DistributionProblem): DistributionValidation.Invalid =
    invalid(listOf(problem))

  private fun invalid(problems: Iterable<DistributionProblem>): DistributionValidation.Invalid =
    DistributionValidation.Invalid(
      immutableList(problems.distinct().sortedBy(DistributionProblem::ordinal)),
    )

  private data class ClasspathValidation(
    val paths: List<Path>,
    val inspections: List<JarInspection>,
    val problems: List<DistributionProblem>,
  )

  private class ValidatedDistribution(
    override val root: Path,
    override val metadata: DistributionMetadata,
    override val benchmarkClasspath: List<Path>,
    override val runnerClasspath: List<Path>,
  ) : VerifiedDistribution

  private companion object {
    const val MINIMUM_JAVA_FEATURE = 21
    val BENCHMARK_LIBRARY = Regex("lib/[A-Za-z0-9_.-]+\\.jar")
    val RUNNER_LIBRARY = Regex("runner/lib/[A-Za-z0-9_.-]+\\.jar")
    val PROJECT_BUILT_JARS =
      setOf(
        DistributionLayout.PRODUCTION_JAR,
        DistributionLayout.BENCHMARK_JAR,
        DistributionLayout.RUNNER_JAR,
      )
    val PROTOCOL_SCHEMA =
      Regex("protocol/schemas/[A-Za-z0-9_.-]+-v[0-9]+\\.schema\\.json")
    val TEST_DEPENDENCY_MARKER =
      Regex("(^|[:/_.-])(junit|kotest|mockk|byte-buddy|bytebuddy)([:/_.-]|$)")
    val REQUIRED_SCHEMAS =
      setOf(
        "protocol/schemas/calibration-provisional-v1.schema.json",
        "protocol/schemas/campaign-v1.schema.json",
        "protocol/schemas/capture-provisional-v1.schema.json",
        "protocol/schemas/capture-profile-family-v1.schema.json",
        "protocol/schemas/capture-v1.schema.json",
        "protocol/schemas/comparison-v1.schema.json",
        "protocol/schemas/distribution-classpath-v1.schema.json",
        "protocol/schemas/distribution-provenance-v1.schema.json",
        "protocol/schemas/distribution-protocol-v1.schema.json",
        "protocol/schemas/expected-cells-v1.schema.json",
        "protocol/schemas/postflight-v1.schema.json",
        "protocol/schemas/preflight-v1.schema.json",
        "protocol/schemas/profiler-summary-v1.schema.json",
        "protocol/schemas/regression-policy-v1.schema.json",
        "protocol/schemas/restoration-v1.schema.json",
        "protocol/schemas/watcher-v1.schema.json",
      )
    val REQUIRED_LAUNCHERS =
      setOf(
        DistributionLayout.UNIX_LAUNCHER,
        DistributionLayout.WINDOWS_LAUNCHER,
      )
    val REQUIRED_PROFILES =
      setOf(
        "protocol/profiles/canary.json",
        "protocol/profiles/cold.json",
        "protocol/profiles/warm.json",
      )
    val REQUIRED_RUNTIMES =
      setOf(
        "protocol/runtime/github-hosted.json",
        "protocol/runtime/linux-arm64.json",
        "protocol/runtime/m4max-docker.json",
      )
    val REQUIRED_POLICIES =
      setOf(
        "protocol/qualification/github-hosted.json",
        "protocol/qualification/m4max-docker.json",
      )
    val REQUIRED_TEST_VECTORS = setOf("protocol/test-vectors/bootstrap-v1.json")
    val REQUIRED_TOOL_IDENTITIES =
      setOf(
        "gradle",
        "javaExecutableSha256",
        "javaFeature",
        "jmhCore",
        "jmhGradlePlugin",
        "kotlinCompiler",
        "runtimeImage",
      )
  }
}
