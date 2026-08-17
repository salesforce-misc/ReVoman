/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.compare

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

internal data class CalibrationCaptureRef(
  val captureId: String,
  val captureSha256: Sha256,
  val bundleSha256: Sha256,
  val treatmentGitSha: String,
  val productionSha256: Sha256,
)

internal object CalibrationBundleVerifier {
  class Projection internal constructor(
    mint: Any,
    val bundleSha256: Sha256,
    val a1CaptureId: String,
    val a2CaptureId: String,
    val baseline: CalibrationCaptureRef,
    val candidate: CalibrationCaptureRef,
    val execution: ComparisonExecutionIdentity,
    val passingCells: Set<CellIdentity>,
  ) {
    init {
      require(mint === PROJECTION_MINT) { "calibration projection must be verifier-minted" }
    }
  }

  class Verification internal constructor(
    val projection: Projection?,
    val failures: List<CompatibilityFailure>,
  )

  fun verify(rootInput: Path): Verification {
    val root = rootInput.toAbsolutePath().normalize()
    if (!validLayout(root)) return invalid()
    val snapshot = snapshot(root) ?: return invalid()
    val manifestBytes = snapshot[CHECKSUMS] ?: return invalid()
    if (!validChecksums(snapshot, manifestBytes)) return invalid()
    val comparisonBytes = snapshot[COMPARISON_JSON] ?: return invalid()
    val document =
      runCatching { CanonicalJson.parseStrict(comparisonBytes) as? ObjectNode }.getOrNull()
        ?: return invalid()
    if (
      !CanonicalJson.encode(document).contentEquals(comparisonBytes) ||
        EvidenceSchemaValidator().validate(SchemaKind.COMPARISON, comparisonBytes).isNotEmpty()
    ) {
      return invalid()
    }
    val projection =
      runCatching { projection(document, Sha256.digest(manifestBytes)) }.getOrNull()
        ?: return invalid()
    return Verification(projection, emptyList())
  }

  fun validate(
    calibration: Projection,
    baseline: CaptureBundleVerifier.Projection,
    candidate: CaptureBundleVerifier.Projection,
    execution: ComparisonExecutionIdentity,
  ): List<CompatibilityFailure> {
    val expectedBaseline = baseline.captureRef()
    val cellsMatch =
      calibration.passingCells == baseline.cells.toSet() &&
        calibration.passingCells == candidate.cells.toSet()
    val a2AndExecutionMatch =
      calibration.a2CaptureId == baseline.identity.captureId &&
        calibration.candidate == expectedBaseline &&
        calibration.execution == execution &&
        cellsMatch
    val orderAndDurationMatch =
      baseline.identity.performanceSessionId == candidate.identity.performanceSessionId &&
        candidate.identity.sessionSequence == baseline.identity.sessionSequence + 1 &&
        !candidate.startedAt.isBefore(baseline.completedAt) &&
        Duration.between(baseline.startedAt, candidate.completedAt) <= MAX_SESSION_DURATION
    return buildList {
        if (!a2AndExecutionMatch) add(CompatibilityFailure.CALIBRATION_EVIDENCE_MISMATCH)
        if (!orderAndDurationMatch) add(CompatibilityFailure.IDENTITY_ORDER_INVALID)
      }
      .sortedBy(Enum<*>::name)
  }

  private fun projection(document: ObjectNode, bundleSha256: Sha256): Projection {
    require(document.text("schemaVersion") == "comparison-v1")
    require(document.text("kind") == "calibration")
    require(document.text("strength") == "diagnostic")
    require(document.text("compatibility") == "compatible")
    require(document.array("compatibilityReasons").isEmpty)
    val calibration = document.objectNode("calibration")
    require(calibration.get("passed").asBoolean())
    require(calibration.get("evidenceSha256") == null && calibration.get("bCaptureId") == null)
    val baseline = document.objectNode("baseline").captureRef()
    val candidate = document.objectNode("candidate").captureRef()
    require(calibration.text("a1CaptureId") == baseline.captureId)
    require(calibration.text("a2CaptureId") == candidate.captureId)
    require(baseline.captureId != candidate.captureId)
    require(baseline.treatmentGitSha == candidate.treatmentGitSha)
    require(baseline.productionSha256 == candidate.productionSha256)
    val policy = document.objectNode("policy")
    val regressionBudget = policy.get("maximumRegressionBudget")?.asDouble()
    if (regressionBudget != null) {
      val policyBytes =
        CanonicalJson.encode(
          tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().apply {
            put("schemaVersion", "regression-policy-v1")
            put("maximumRegressionBudget", regressionBudget)
          }
        )
      require(policy.sha("sha256") == Sha256.digest(policyBytes))
      require(
        policy.get("maximumCandidateBaselineRatio").asDouble() == 1.0 + regressionBudget,
      )
    }
    val cellPolicies = mutableListOf<PolicyOutcome>()
    val passingCells =
      document.array("cells").values().asSequence().map { value ->
        val cell = value.asObject()
        val estimate = cell.objectNode("estimate")
        val pointRatio = estimate.get("pointRatio").asDouble()
        val lowerRatio = estimate.get("lower95Ratio").asDouble()
        val upperRatio = estimate.get("upper95Ratio").asDouble()
        require(
          estimate.get("gainPercent").asDouble() == (1.0 - pointRatio) * 100.0 &&
            cell.text("directionOutcome") ==
              CaptureComparator.directionForTesting(lowerRatio, upperRatio).wire()
        )
        val expectedPolicy =
          regressionBudget?.let { budget ->
            CaptureComparator.policyForTesting(
              lowerRatio,
              upperRatio,
              budget,
            )
          } ?: PolicyOutcome.NOT_ENFORCED
        require(cell.text("policyOutcome") == expectedPolicy.wire())
        cellPolicies += expectedPolicy
        require(
          CalibrationQualification.passes(
            pointRatio,
            lowerRatio,
            upperRatio,
          )
        )
        cell.objectNode("identity").cellIdentity()
      }.toList()
    require(passingCells.isNotEmpty() && passingCells.distinct().size == passingCells.size)
    val expectedOverall =
      if (regressionBudget == null) {
        PolicyOutcome.NOT_ENFORCED
      } else {
        CaptureComparator.aggregatePolicyForTesting(cellPolicies)
      }
    require(policy.text("outcome") == expectedOverall.wire())
    return Projection(
      mint = PROJECTION_MINT,
      bundleSha256 = bundleSha256,
      a1CaptureId = calibration.text("a1CaptureId"),
      a2CaptureId = calibration.text("a2CaptureId"),
      baseline = baseline,
      candidate = candidate,
      execution = document.objectNode("implementation").execution(),
      passingCells = immutableSet(passingCells),
    )
  }

  private fun validLayout(root: Path): Boolean {
    if (
      hasSymbolicLinkComponent(root) ||
        !Files.isDirectory(root, NOFOLLOW_LINKS) ||
        Files.isSymbolicLink(root)
    ) {
      return false
    }
    val entries = runCatching { Files.list(root).use { it.toList() } }.getOrNull() ?: return false
    return entries.map { it.fileName.toString() }.toSet() == REQUIRED_FILES &&
      entries.all { path -> Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) }
  }

  private fun snapshot(root: Path): Map<String, ByteArray>? =
    runCatching {
        Files.list(root).use { paths ->
          paths.toList().associate { path -> path.fileName.toString() to Files.readAllBytes(path) }
        }
      }
      .getOrNull()

  private fun validChecksums(snapshot: Map<String, ByteArray>, bytes: ByteArray): Boolean {
    val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return false
    if (!text.endsWith('\n')) return false
    val lines = text.dropLast(1).split('\n')
    val entries = lines.mapNotNull { line ->
      CHECKSUM_LINE.matchEntire(line)?.destructured?.let { (sha, relative) -> sha to relative }
    }
    val expected = snapshot.keys.filter { it != CHECKSUMS }.sorted()
    return entries.size == lines.size &&
      entries.map(Pair<String, String>::second) == expected &&
      entries.all { (sha, relative) -> snapshot[relative]?.let(Sha256::digest)?.hex == sha }
  }

  private fun invalid(): Verification =
    Verification(null, listOf(CompatibilityFailure.CALIBRATION_EVIDENCE_INVALID))

  private const val COMPARISON_JSON = "comparison.json"
  private const val CHECKSUMS = "checksums.sha256"
  private val REQUIRED_FILES = setOf(COMPARISON_JSON, "comparison.md", CHECKSUMS)
  private val CHECKSUM_LINE = Regex("([0-9a-f]{64})  ([A-Za-z0-9._-]+)")
  private val MAX_SESSION_DURATION = Duration.ofHours(2)
  private val PROJECTION_MINT = Any()
}

private fun CaptureBundleVerifier.Projection.captureRef(): CalibrationCaptureRef =
  CalibrationCaptureRef(
    captureId = identity.captureId,
    captureSha256 = captureSha256,
    bundleSha256 = bundleSha256,
    treatmentGitSha = provenance.treatment.gitSha,
    productionSha256 = artifacts.production.sha256,
  )

private fun ObjectNode.captureRef(): CalibrationCaptureRef =
  CalibrationCaptureRef(
    captureId = text("captureId"),
    captureSha256 = sha("captureSha256"),
    bundleSha256 = sha("bundleSha256"),
    treatmentGitSha = text("treatmentGitSha"),
    productionSha256 = sha("productionSha256"),
  )

private fun ObjectNode.execution(): ComparisonExecutionIdentity =
  ComparisonExecutionIdentity(
    runnerSha256 = sha("runnerSha256"),
    protocolSha256 = sha("protocolSha256"),
    adapterSha256 = sha("adapterSha256"),
    expectedCellsSha256 = sha("expectedCellsSha256"),
    captureSchemaSha256 = sha("captureSchemaSha256"),
    comparisonSchemaSha256 = sha("comparisonSchemaSha256"),
    bootstrapVectorSha256 = sha("bootstrapVectorSha256"),
    comparatorSha256 = sha("comparatorSha256"),
    rendererSha256 = sha("rendererSha256"),
    qualificationPolicySha256 = sha("qualificationPolicySha256"),
  )

private fun ObjectNode.cellIdentity(): CellIdentity =
  CellIdentity(
    benchmark = text("benchmark"),
    profile = text("profile"),
    parameters =
      immutableMap(
        objectNode("parameters").properties().associate { (name, value) ->
          require(value.isTextual)
          name to value.asString()
        }
      ),
    mode = text("mode"),
    unit = text("unit"),
    threads = get("threads").asInt(),
    batchSize = get("batchSize").asInt(),
    primaryMetric = text("primaryMetric"),
    direction = text("direction"),
  )

private fun hasSymbolicLinkComponent(path: Path): Boolean {
  var current = path.root ?: return true
  path.forEach { component ->
    current = current.resolve(component)
    if (Files.isSymbolicLink(current)) return true
  }
  return false
}

private fun <T> immutableSet(values: Collection<T>): Set<T> =
  Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
  Collections.unmodifiableMap(LinkedHashMap(values))

private fun ObjectNode.text(name: String): String = get(name).asString()

private fun ObjectNode.sha(name: String): Sha256 = Sha256.parse(text(name))

private fun ObjectNode.objectNode(name: String): ObjectNode = get(name).asObject()

private fun ObjectNode.array(name: String) = get(name).asArray()

private fun PolicyOutcome.wire(): String = name.lowercase().replace('_', '-')

private fun DirectionOutcome.wire(): String = name.lowercase()
