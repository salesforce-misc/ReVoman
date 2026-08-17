/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import java.math.BigDecimal
import java.math.MathContext
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.CaptureCell
import performance.model.ForkSummary
import performance.model.JmhResultRowRef
import performance.model.PrimaryMetricIdentity
import performance.model.SampleDimensions
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

sealed interface JmhCanonicalization {
  data class Valid(
    val canonicalBytes: ByteArray,
    val rawInputSha256: Sha256,
    val cells: List<CaptureCell>,
    val secondaryMetricScores: Map<String, BigDecimal>,
  ) : JmhCanonicalization

  data class Invalid(val reasons: List<CaptureFailure>) : JmhCanonicalization
}

/** Projects JMH output onto the strict observation fields owned by the V1 protocol. */
class JmhResultCanonicalizer(
  private val validator: JmhResultValidator = JmhResultValidator(),
) {
  fun canonicalize(
    rawBytes: ByteArray,
    geometry: CaptureGeometry,
    expectedCells: ExpectedCells,
  ): JmhCanonicalization {
    if (rawBytes.isEmpty()) return JmhCanonicalization.Invalid(listOf(CaptureFailure.RESULT_EMPTY))
    val text = rawBytes.decodeToString().trimStart()
    if (!text.startsWith("[") && text.lineSequence().firstOrNull()?.contains(',') == true) {
      return JmhCanonicalization.Invalid(listOf(CaptureFailure.RESULT_HEADER_ONLY))
    }
    val rows =
      runCatching { relaxedMapper.readTree(rawBytes) as? ArrayNode }
        .getOrNull()
        ?: return JmhCanonicalization.Invalid(listOf(CaptureFailure.RESULT_MALFORMED))
    val failures = validator.validate(rows, geometry, expectedCells)
    if (failures.isNotEmpty()) return JmhCanonicalization.Invalid(failures)

    val rowsByKey = validator.parseValidatedRows(rows).associateBy(ParsedJmhRow::key)
    val canonicalRows = JsonNodeFactory.instance.arrayNode()
    val cells = mutableListOf<CaptureCell>()
    val secondaryScores = linkedMapOf<String, BigDecimal>()
    expectedCells.cells.forEachIndexed { index, expected ->
      val parsed = rowsByKey.getValue(expected.key)
      val canonicalRow = canonicalRow(parsed.json)
      canonicalRows.add(canonicalRow)
      val primary = canonicalRow.get("primaryMetric") as ObjectNode
      val rawData = primary.get("rawData") as ArrayNode
      val rowBytes = CanonicalJson.encode(canonicalRow)
      cells +=
        CaptureCell(
          benchmark = expected.benchmark,
          parameters = expected.parameters.toSortedMap(),
          mode = geometry.mode,
          unit = "${geometry.unit}/op",
          threads = geometry.threads,
          batchSize = geometry.batchSize,
          primaryMetric = PrimaryMetricIdentity("score", "lowerIsBetter"),
          jmhResultRow = JmhResultRowRef("/$index", Sha256.digest(rowBytes)),
          sampleDimensions =
            SampleDimensions(
              forks = geometry.forks,
              measurementIterations = geometry.measurementIterations,
              samplesPerFork = geometry.measurementIterations,
            ),
          derivedForkSummaries = forkSummaries(rawData),
        )
      secondaryScores.putAll(extractSecondaryScores(parsed.json))
    }
    return JmhCanonicalization.Valid(
      canonicalBytes = CanonicalJson.encode(canonicalRows),
      rawInputSha256 = Sha256.digest(rawBytes),
      cells = cells.toList(),
      secondaryMetricScores = secondaryScores.toMap(),
    )
  }

  private fun canonicalRow(source: ObjectNode): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("benchmark", source.get("benchmark").asString())
      put("forks", source.get("forks").asInt())
      put("measurementBatchSize", source.get("measurementBatchSize").asInt())
      put("measurementIterations", source.get("measurementIterations").asInt())
      put("mode", source.get("mode").asString())
      set("params", canonicalParameters(source.get("params")))
      set("primaryMetric", canonicalPrimary(source.get("primaryMetric") as ObjectNode))
      set("secondaryMetrics", canonicalSecondary(source.get("secondaryMetrics")))
      put("threads", source.get("threads").asInt())
      put("warmupBatchSize", source.get("warmupBatchSize").asInt())
      put("warmupIterations", source.get("warmupIterations").asInt())
    }

  private fun canonicalParameters(value: JsonNode?): ObjectNode =
    JsonNodeFactory.instance.objectNode().also { target ->
      (value as? ObjectNode)
        ?.properties()
        ?.sortedBy { it.key }
        ?.forEach { (name, parameter) -> target.put(name, parameter.asString()) }
    }

  private fun canonicalPrimary(source: ObjectNode): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      set("rawData", source.get("rawData").deepCopy())
      put("scoreUnit", source.get("scoreUnit").asString())
    }

  private fun canonicalSecondary(value: JsonNode?): ObjectNode =
    JsonNodeFactory.instance.objectNode().also { target ->
      (value as? ObjectNode)
        ?.properties()
        ?.filter { it.key in ALLOWED_GC_METRICS }
        ?.sortedBy { it.key }
        ?.forEach { (name, metricValue) ->
          val metric = metricValue as? ObjectNode ?: return@forEach
          val score = metric.get("score")
          if (score != null && score.isNumber && score.asDouble().isFinite()) {
            target.set(
              name,
              JsonNodeFactory.instance.objectNode().apply {
                put("score", score.decimalValue())
                metric.get("scoreUnit")?.takeIf(JsonNode::isTextual)?.let {
                  put("scoreUnit", it.asString())
                }
              },
            )
          }
        }
    }

  private fun forkSummaries(rawData: ArrayNode): List<ForkSummary> =
    rawData.mapIndexed { index, forkValue ->
      val observations =
        (forkValue as ArrayNode).values().asSequence().map(JsonNode::decimalValue).toList()
      ForkSummary(
        fork = index + 1,
        sampleCount = observations.size,
        score = observations.reduce(BigDecimal::add).divide(BigDecimal(observations.size), MathContext.DECIMAL128),
      )
    }

  private fun extractSecondaryScores(row: ObjectNode): Map<String, BigDecimal> =
    (row.get("secondaryMetrics") as? ObjectNode)
      ?.properties()
      ?.mapNotNull { (name, value) ->
        if (name !in ALLOWED_GC_METRICS) return@mapNotNull null
        val score = (value as? ObjectNode)?.get("score") ?: return@mapNotNull null
        if (!score.isNumber || !score.asDouble().isFinite()) null else name to score.decimalValue()
      }
      ?.toMap()
      .orEmpty()

  private companion object {
    val ALLOWED_GC_METRICS = setOf("gc.alloc.rate.norm", "gc.count", "gc.time")
    val relaxedMapper: JsonMapper =
      JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
        .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
        .build()
  }
}
