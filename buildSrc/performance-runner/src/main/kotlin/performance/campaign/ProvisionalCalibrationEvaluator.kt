/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.campaign

import java.math.BigDecimal
import java.math.MathContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.util.HexFormat
import performance.capture.CaptureProfile
import performance.capture.DiagnosticProfiler
import performance.compare.BootstrapV1
import performance.compare.CalibrationQualification
import performance.compare.CellIdentity
import performance.compare.ForkSamples
import performance.compare.RatioEstimate
import performance.distribution.DistributionClasspathEntry
import performance.distribution.DistributionLayout
import performance.distribution.VerifiedDistribution
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.ArtifactIdentity
import performance.model.CaptureCell
import performance.model.CaptureProfileIdentity
import performance.model.DependencyIdentity
import performance.model.EvidenceStatus
import performance.model.ProvisionalCaptureDocument
import performance.model.ProvisionalEvidenceStrength
import performance.model.ProvisionalOutcomeReason
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** Validated unpublished observations; this type is not evidence and has no public constructor. */
@ConsistentCopyVisibility
internal data class ValidatedProvisionalCapture private constructor(
  val role: CaptureRole,
  val root: Path,
  val document: ProvisionalCaptureDocument,
  val samples: Map<CellIdentity, List<ForkSamples>>,
) {
  companion object {
    fun verify(
      role: CaptureRole,
      root: Path,
      document: ProvisionalCaptureDocument,
      expectedProfile: CaptureProfile,
      distribution: VerifiedDistribution,
    ): ValidatedProvisionalCapture {
      require(document.schemaVersion == "capture-provisional-v1")
      require(document.benchmarkProtocolVersion == "performance-v1")
      require(document.outcome.status == EvidenceStatus.VALID && document.outcome.processExit == 0)
      require(document.outcome.strength == ProvisionalEvidenceStrength.DIAGNOSTIC)
      require(document.outcome.reasons == listOf(ProvisionalOutcomeReason.BOUNDED_DIAGNOSTIC))
      require(
        !Instant.parse(document.outcome.completedAtUtc)
          .isBefore(Instant.parse(document.outcome.startedAtUtc)),
      )
      require(document.profile == expectedProfile.identity())
      require(document.profile.profiler == DiagnosticProfiler.NONE.id)
      require(document.rawProfilerInputSha256 == null)
      require(document.provenance == expectedProfile.evidence.provenance)
      require(document.protocol == expectedProfile.evidence.protocol)
      require(document.toolchain == expectedProfile.evidence.toolchain)
      require(document.runtime == expectedProfile.evidence.runtime)
      require(document.logging == expectedProfile.evidence.logging)
      require(document.artifacts.matches(distribution))
      require(
        document.cells.map { cell -> cell.benchmark to cell.parameters } ==
          expectedProfile.expectedCells.cells.map { cell -> cell.benchmark to cell.parameters },
      )
      val resultPath = root.resolve(JMH_RESULT)
      require(
        Files.isRegularFile(resultPath, NOFOLLOW_LINKS) && !Files.isSymbolicLink(resultPath),
      )
      val bytes = Files.readAllBytes(resultPath)
      val rows = CanonicalJson.parseStrict(bytes) as? ArrayNode ?: error("JMH result root")
      require(CanonicalJson.encode(rows).contentEquals(bytes))
      require(rows.size() == document.cells.size && document.cells.isNotEmpty())
      val samples =
        document.cells.mapIndexed { index, cell ->
          val row = rows[index] as? ObjectNode ?: error("JMH result row")
          verifyRow(index, row, cell, expectedProfile)
          cell.identity(document.profile.family) to forkSamples(row, cell)
        }
      require(samples.map { sample -> sample.first }.distinct().size == samples.size)
      return ValidatedProvisionalCapture(
        role = role,
        root = root.toAbsolutePath().normalize(),
        document = document,
        samples = samples.toMap(),
      )
    }

    private fun verifyRow(
      index: Int,
      row: ObjectNode,
      cell: CaptureCell,
      profile: CaptureProfile,
    ) {
      require(row.properties().map { it.key }.toSet() == JMH_ROW_FIELDS)
      val expectedCell = profile.expectedCells.cells[index]
      require(cell.benchmark == expectedCell.benchmark)
      require(cell.parameters == expectedCell.parameters.toSortedMap())
      require(cell.mode == profile.mode)
      require(cell.unit == "${profile.unit}/op")
      require(cell.threads == profile.threads)
      require(cell.batchSize == profile.batchSize)
      require(cell.primaryMetric.name == "score" && cell.primaryMetric.direction == "lowerIsBetter")
      require(cell.sampleDimensions.forks == profile.forks)
      require(cell.sampleDimensions.measurementIterations == profile.measurementIterations)
      require(cell.sampleDimensions.samplesPerFork == profile.measurementIterations)
      require(cell.jmhResultRow.jsonPointer == "/$index")
      require(cell.jmhResultRow.sha256 == Sha256.digest(CanonicalJson.encode(row)))
      require(row.get("benchmark").asString() == cell.benchmark)
      require(row.get("mode").asString() == cell.mode)
      require(row.get("threads").asInt() == cell.threads)
      require(row.get("forks").asInt() == cell.sampleDimensions.forks)
      require(row.get("warmupIterations").asInt() == profile.warmupIterations)
      require(row.get("warmupBatchSize").asInt() == profile.batchSize)
      require(
        row.get("measurementIterations").asInt() ==
          cell.sampleDimensions.measurementIterations,
      )
      require(row.get("measurementBatchSize").asInt() == cell.batchSize)
      val parameters =
        row
          .get("params")
          .asObject()
          .properties()
          .associate { (name, value) -> name to value.asString() }
      require(parameters == cell.parameters)
      val primary = row.get("primaryMetric") as? ObjectNode ?: error("primary metric")
      require(primary.properties().map { it.key }.toSet() == PRIMARY_METRIC_FIELDS)
      require(primary.get("scoreUnit").asString() == cell.unit)
      require(row.get("secondaryMetrics") is ObjectNode)
      val forks = primary.get("rawData") as? ArrayNode ?: error("raw data")
      require(forks.size() == cell.sampleDimensions.forks)
      require(cell.derivedForkSummaries.size == forks.size())
      forks.forEachIndexed { forkIndex, value ->
        val observations = value as? ArrayNode ?: error("fork observations")
        require(observations.size() == cell.sampleDimensions.samplesPerFork)
        val decimals =
          observations.values().asSequence().map { observation -> observation.decimalValue() }.toList()
        require(decimals.all { observation -> observation.signum() > 0 && observation.toDouble().isFinite() })
        val mean =
          decimals
            .reduce(BigDecimal::add)
            .divide(BigDecimal(decimals.size), MathContext.DECIMAL128)
        val summary = cell.derivedForkSummaries[forkIndex]
        require(
          summary.fork == forkIndex + 1 &&
            summary.sampleCount == decimals.size &&
            summary.score.compareTo(mean) == 0,
        )
      }
    }

    private fun forkSamples(row: ObjectNode, cell: CaptureCell): List<ForkSamples> =
      (row.get("primaryMetric").get("rawData") as ArrayNode)
        .values()
        .asSequence()
        .map { fork ->
          val values =
            (fork as ArrayNode)
              .values()
              .asSequence()
              .map { observation -> observation.doubleValue() }
              .toList()
          require(
            values.size == cell.sampleDimensions.samplesPerFork &&
              values.all { value -> value.isFinite() && value > 0.0 },
          )
          ForkSamples(values)
        }
        .toList()

    private const val JMH_RESULT = "jmh-result.json"
    private val JMH_ROW_FIELDS =
      setOf(
        "benchmark",
        "forks",
        "measurementBatchSize",
        "measurementIterations",
        "mode",
        "params",
        "primaryMetric",
        "secondaryMetrics",
        "threads",
        "warmupBatchSize",
        "warmupIterations",
      )
    private val PRIMARY_METRIC_FIELDS = setOf("rawData", "scoreUnit")
  }
}

internal data class ProvisionalCellCalibration(
  val identity: CellIdentity,
  val estimate: RatioEstimate,
  val passed: Boolean,
)

internal data class ProvisionalCalibrationDecision(
  val cells: List<ProvisionalCellCalibration>,
) {
  val passed: Boolean = cells.isNotEmpty() && cells.all(ProvisionalCellCalibration::passed)
}

/** Pure provisional evaluator; its result can authorize B but cannot render or publish evidence. */
class ProvisionalCalibrationEvaluator {
  internal fun evaluate(
    baseline: ValidatedProvisionalCapture,
    candidate: ValidatedProvisionalCapture,
  ): ProvisionalCalibrationDecision {
    require(baseline.role == CaptureRole.BASELINE_A1)
    require(candidate.role == CaptureRole.BASELINE_A2)
    require(
      baseline.document.identity.performanceSessionId ==
        candidate.document.identity.performanceSessionId,
    )
    require(
      candidate.document.identity.sessionSequence ==
        baseline.document.identity.sessionSequence + 1,
    )
    require(baseline.document.identity.captureId != candidate.document.identity.captureId)
    require(baseline.samples.keys == candidate.samples.keys)
    val cells =
      baseline.samples.keys.sortedBy { identity -> identity.canonicalBytes().toHex() }.map { identity ->
        val estimate =
          BootstrapV1.estimate(
            baselineCaptureId = baseline.document.identity.captureId,
            candidateCaptureId = candidate.document.identity.captureId,
            cell = identity,
            baseline = baseline.samples.getValue(identity),
            candidate = candidate.samples.getValue(identity),
          )
        ProvisionalCellCalibration(
          identity = identity,
          estimate = estimate,
          passed =
            CalibrationQualification.passes(
              estimate.pointRatio,
              estimate.lower95Ratio,
              estimate.upper95Ratio,
            ),
        )
      }
    return ProvisionalCalibrationDecision(cells)
  }
}

private fun CaptureProfile.identity(): CaptureProfileIdentity =
  CaptureProfileIdentity(
    family = family.id,
    identity = identity,
    variantSha256 = variantSha256,
    forks = forks,
    warmupIterations = warmupIterations,
    measurementIterations = measurementIterations,
    profiler = profiler.id,
  )

private fun performance.model.CaptureArtifacts.matches(
  verifiedDistribution: VerifiedDistribution,
): Boolean {
  val classpath = verifiedDistribution.metadata.classpath
  val expectedBenchmark =
    classpath.benchmarkClasspath.single { entry ->
      entry.path == DistributionLayout.BENCHMARK_JAR
    }
  val expectedProduction =
    classpath.benchmarkClasspath.single { entry ->
      entry.path == DistributionLayout.PRODUCTION_JAR
    }
  val expectedRunner = classpath.runnerClasspath.first()
  val expectedDependencies =
    classpath.benchmarkClasspath
      .filterNot { entry -> entry == expectedBenchmark || entry == expectedProduction }
      .map { entry -> DependencyIdentity(entry.coordinate, entry.sha256) } +
      classpath.embeddedDependencies.map { dependency ->
        DependencyIdentity(dependency.coordinate, dependency.sha256)
      }
  return production == expectedProduction.artifact() &&
    benchmark == expectedBenchmark.artifact() &&
    distribution.path == DistributionLayout.CHECKSUM_MANIFEST &&
    distribution.sha256 ==
      Sha256.digest(
        verifiedDistribution.root.resolve(DistributionLayout.CHECKSUM_MANIFEST),
      ) &&
    orderedClasspath == classpath.benchmarkClasspath.map(DistributionClasspathEntry::artifact) &&
    executingRunner == expectedRunner.artifact() &&
    orderedRunnerClasspath == classpath.runnerClasspath.map(DistributionClasspathEntry::artifact) &&
    dependencies == expectedDependencies
}

private fun DistributionClasspathEntry.artifact(): ArtifactIdentity = ArtifactIdentity(path, sha256)

private fun CaptureCell.identity(profile: String): CellIdentity =
  CellIdentity(
    benchmark = benchmark,
    profile = profile,
    parameters = parameters,
    mode = mode,
    unit = unit,
    threads = threads,
    batchSize = batchSize,
    primaryMetric = primaryMetric.name,
    direction =
      when (primaryMetric.direction) {
        "lowerIsBetter" -> "lower-is-better"
        else -> primaryMetric.direction
      },
  )

private fun ByteArray.toHex(): String = HexFormat.of().formatHex(this)
