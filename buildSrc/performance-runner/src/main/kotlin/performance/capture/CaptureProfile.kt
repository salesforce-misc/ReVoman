/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import java.nio.file.Path
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.LoggingProfileIdentity
import performance.model.ProtocolIdentity
import performance.model.ProvenanceRoles
import performance.model.RuntimeIdentity
import performance.model.ToolchainIdentity
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

enum class CaptureProfileFamily(val id: String) {
  CANARY("canary"),
  COLD("cold"),
  WARM("warm"),
}

enum class DiagnosticProfiler(val id: String) {
  NONE("none"),
  GC("gc"),
  JFR("jfr"),
}

internal const val JFR_FORK_ACCUMULATOR =
  "com.salesforce.revoman.benchmark.JfrForkAccumulator"

internal const val JFR_PROFILER_ARGUMENT =
  "jfr:dir={operationRoot};configName=profile;debugNonSafePoints=true;stackDepth=1024;postProcessor=$JFR_FORK_ACCUMULATOR;verbose=false"

/** Immutable execution dimensions checked against both the command and returned rows. */
data class CaptureGeometry(
  val forks: Int,
  val warmupIterations: Int,
  val measurementIterations: Int,
  val batchSize: Int,
  val threads: Int,
  val mode: String,
  val unit: String,
)

/** Non-result evidence already verified by the container bootstrap and frozen distribution. */
data class CaptureEvidenceContext(
  val provenance: ProvenanceRoles,
  val protocol: ProtocolIdentity,
  val toolchain: ToolchainIdentity,
  val runtime: RuntimeIdentity,
  val logging: LoggingProfileIdentity,
)

/** One selected immutable variant from a checked-in profile family. */
data class CaptureProfile(
  val family: CaptureProfileFamily,
  val identity: String,
  val variantSha256: Sha256,
  val forks: Int,
  val warmupIterations: Int,
  val measurementIterations: Int,
  val batchSize: Int,
  val threads: Int,
  val mode: String,
  val unit: String,
  val profiler: DiagnosticProfiler,
  val profilerSettingsSha256: Sha256?,
  val profilerArguments: List<String>,
  val jvmArguments: List<String>,
  val expectedCells: ExpectedCells,
  val expectedProtocolSha256: Sha256,
  val selectedJavaExecutable: Path,
  val selectedJavaSha256: Sha256,
  val evidence: CaptureEvidenceContext,
) {
  val geometry: CaptureGeometry =
    CaptureGeometry(
      forks,
      warmupIterations,
      measurementIterations,
      batchSize,
      threads,
      mode,
      unit,
    )

  internal fun isStructurallyValid(): Boolean {
    val validForks =
      when (family) {
        CaptureProfileFamily.CANARY -> forks == 1
        CaptureProfileFamily.COLD,
        CaptureProfileFamily.WARM -> forks in setOf(10, 20, 40)
      }
    val validIterations =
      when (family) {
        CaptureProfileFamily.CANARY -> warmupIterations == 0 && measurementIterations == 1
        CaptureProfileFamily.COLD -> warmupIterations == 0 && measurementIterations == 1
        CaptureProfileFamily.WARM -> warmupIterations == 5 && measurementIterations == 10
      }
    val validProfiler =
      when (profiler) {
        DiagnosticProfiler.NONE -> profilerSettingsSha256 == null && profilerArguments.isEmpty()
        DiagnosticProfiler.GC -> profilerSettingsSha256 == null && profilerArguments == listOf("gc")
        DiagnosticProfiler.JFR ->
          profilerSettingsSha256 != null &&
            profilerArguments == listOf(JFR_PROFILER_ARGUMENT)
      } && (profiler == DiagnosticProfiler.NONE || family == CaptureProfileFamily.WARM)
    return SAFE_ID.matches(identity) &&
      validForks &&
      validIterations &&
      validProfiler &&
      batchSize == 1 &&
      threads == 1 &&
      mode == "ss" &&
      unit == "ms" &&
      selectedJavaExecutable.isAbsolute &&
      jvmArguments.isNotEmpty() &&
      jvmArguments.all { it.isNotBlank() && '\n' !in it && '\r' !in it } &&
      expectedCells.isJmhCliRepresentable() &&
      (profiler != DiagnosticProfiler.JFR || expectedCells.cells.size == 1) &&
      evidence.runtime.jdk.binarySha256 == selectedJavaSha256 &&
      evidence.runtime.jdk.jvmArguments == jvmArguments &&
      evidence.protocol.benchmarkProtocolSha256 == expectedProtocolSha256
  }

  private companion object {
    val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
  }
}

/** Strict profile-family decoder; variant hashes bind the exact canonical variant object. */
object CaptureProfileReader {
  fun read(
    bytes: ByteArray,
    expectedCells: ExpectedCells,
    expectedProtocolSha256: Sha256,
    selectedJavaExecutable: Path,
    selectedJavaSha256: Sha256,
    evidence: CaptureEvidenceContext,
  ): List<CaptureProfile> {
    val root = CanonicalJson.parseStrict(bytes) as? ObjectNode ?: error("profile root")
    require(
      EvidenceSchemaValidator()
        .validate(SchemaKind.CAPTURE_PROFILE_FAMILY, CanonicalJson.encode(root))
        .isEmpty(),
    ) {
      "capture profile schema mismatch"
    }
    require(root.properties().map { it.key }.toSet() == PROFILE_FIELDS)
    require(root.get("schemaVersion").asString() == "capture-profile-family-v1")
    val family = CaptureProfileFamily.entries.single { it.id == root.get("family").asString() }
    val jvmArguments =
      (root.get("jvmArguments") as ArrayNode).values().asSequence().map { it.asString() }.toList()
    val variants = root.get("variants") as ArrayNode
    return variants
      .values()
      .asSequence()
      .map { value ->
        val variant = value as ObjectNode
        require(variant.properties().map { it.key }.toSet() == VARIANT_FIELDS)
        val profiler =
          DiagnosticProfiler.entries.single { it.id == variant.get("profiler").asString() }
        CaptureProfile(
          family = family,
          identity = variant.get("identity").asString(),
          variantSha256 = Sha256.digest(CanonicalJson.encode(variant)),
          forks = variant.get("forks").asInt(),
          warmupIterations = variant.get("warmupIterations").asInt(),
          measurementIterations = variant.get("measurementIterations").asInt(),
          batchSize = root.get("batchSize").asInt(),
          threads = root.get("threads").asInt(),
          mode = root.get("mode").asString(),
          unit = root.get("unit").asString(),
          profiler = profiler,
          profilerSettingsSha256 =
            variant
              .get("profilerSettingsSha256")
              ?.takeUnless { it.isNull }
              ?.asString()
              ?.let(Sha256::parse),
          profilerArguments =
            (variant.get("profilerArguments") as ArrayNode)
              .values()
              .asSequence()
              .map { it.asString() }
              .toList(),
          jvmArguments = jvmArguments,
          expectedCells = expectedCells,
          expectedProtocolSha256 = expectedProtocolSha256,
          selectedJavaExecutable = selectedJavaExecutable,
          selectedJavaSha256 = selectedJavaSha256,
          evidence = evidence,
        ).also { require(it.isStructurallyValid()) }
      }
      .toList()
      .also { profiles ->
        require(profiles.map(CaptureProfile::identity).distinct().size == profiles.size)
        require(profiles.map { it.forks to it.profiler }.toSet() == approvedVariants(family))
        profiles.filter { it.profiler == DiagnosticProfiler.JFR }.forEach { profile ->
          val arguments =
            JsonNodeFactory.instance.arrayNode().apply {
              profile.profilerArguments.forEach(::add)
            }
          require(profile.profilerSettingsSha256 == Sha256.digest(CanonicalJson.encode(arguments)))
        }
      }
  }

  private fun approvedVariants(family: CaptureProfileFamily): Set<Pair<Int, DiagnosticProfiler>> =
    when (family) {
      CaptureProfileFamily.CANARY -> setOf(1 to DiagnosticProfiler.NONE)
      CaptureProfileFamily.COLD ->
        setOf(10, 20, 40).mapTo(mutableSetOf()) { it to DiagnosticProfiler.NONE }
      CaptureProfileFamily.WARM ->
        setOf(10, 20, 40).flatMapTo(mutableSetOf()) { forks ->
          DiagnosticProfiler.entries.map { profiler -> forks to profiler }
        }
    }

  private val PROFILE_FIELDS =
    setOf("\$schema", "schemaVersion", "family", "mode", "unit", "threads", "batchSize", "jvmArguments", "variants")
  private val VARIANT_FIELDS =
    setOf(
      "identity",
      "forks",
      "warmupIterations",
      "measurementIterations",
      "profiler",
      "profilerSettingsSha256",
      "profilerArguments",
    )
}
