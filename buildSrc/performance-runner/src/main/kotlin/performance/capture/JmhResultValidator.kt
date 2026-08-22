/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import java.math.BigDecimal
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** Semantic validation for the exact JMH row set and primary observation geometry. */
internal class JmhResultValidator {
  fun validate(
    rows: ArrayNode,
    geometry: CaptureGeometry,
    expectedCells: ExpectedCells,
  ): List<CaptureFailure> {
    if (rows.isEmpty) return listOf(CaptureFailure.RESULT_HAS_ZERO_ROWS)
    val failures = linkedSetOf<CaptureFailure>()
    val parsedRows = rows.values().asSequence().mapNotNull { parseRow(it, failures) }.toList()
    val actualKeys = parsedRows.map(ParsedJmhRow::key)
    if (actualKeys.distinct().size != actualKeys.size) failures += CaptureFailure.DUPLICATE_RESULT_ROW
    if (!actualKeys.containsAll(expectedCells.keys)) failures += CaptureFailure.MISSING_RESULT_ROW
    if (!expectedCells.keys.containsAll(actualKeys)) failures += CaptureFailure.EXTRA_RESULT_ROW
    parsedRows.forEach { row -> validateRow(row, geometry, failures) }
    return failures.toList()
  }

  internal fun parseValidatedRows(rows: ArrayNode): List<ParsedJmhRow> =
    rows.values().asSequence().map { value ->
      val failures = linkedSetOf<CaptureFailure>()
      checkNotNull(parseRow(value, failures))
    }.toList()

  private fun parseRow(
    value: JsonNode,
    failures: MutableSet<CaptureFailure>,
  ): ParsedJmhRow? {
    val row = value as? ObjectNode
    if (row == null) {
      failures += CaptureFailure.RESULT_ROW_MALFORMED
      return null
    }
    val benchmark = row.textOrNull("benchmark")
    val parameters = parseParameters(row.get("params"), failures)
    if (benchmark == null || parameters == null) {
      failures += CaptureFailure.RESULT_ROW_MALFORMED
      return null
    }
    return ParsedJmhRow(row, CellKey(benchmark, parameters))
  }

  private fun parseParameters(
    value: JsonNode?,
    failures: MutableSet<CaptureFailure>,
  ): Map<String, String>? {
    if (value == null || value.isNull) return emptyMap()
    val parameters = value as? ObjectNode
    if (parameters == null || parameters.properties().any { !it.value.isTextual }) {
      failures += CaptureFailure.RESULT_ROW_MALFORMED
      return null
    }
    return parameters.properties().associate { (name, parameter) -> name to parameter.asString() }.toSortedMap()
  }

  private fun validateRow(
    row: ParsedJmhRow,
    geometry: CaptureGeometry,
    failures: MutableSet<CaptureFailure>,
  ) {
    val json = row.json
    if (
      json.integer("forks") != geometry.forks ||
        json.integer("warmupIterations") != geometry.warmupIterations ||
        json.integer("measurementIterations") != geometry.measurementIterations ||
        json.integer("warmupBatchSize") != geometry.batchSize ||
        json.integer("measurementBatchSize") != geometry.batchSize ||
        json.integer("threads") != geometry.threads ||
        json.textOrNull("mode") != geometry.mode
    ) {
      failures += CaptureFailure.RESULT_GEOMETRY_MISMATCH
    }
    val primary = json.get("primaryMetric") as? ObjectNode
    if (primary == null || primary.textOrNull("scoreUnit") != "${geometry.unit}/op") {
      failures += CaptureFailure.RESULT_GEOMETRY_MISMATCH
      return
    }
    val rawData = primary.get("rawData") as? ArrayNode
    if (rawData == null || rawData.size() != geometry.forks) {
      failures += CaptureFailure.RESULT_GEOMETRY_MISMATCH
      return
    }
    rawData.forEach { forkValue ->
      val fork = forkValue as? ArrayNode
      if (fork == null || fork.size() != geometry.measurementIterations) {
        failures += CaptureFailure.RESULT_GEOMETRY_MISMATCH
      } else {
        fork.forEach { observation -> validateObservation(observation, failures) }
      }
    }
  }

  private fun validateObservation(
    observation: JsonNode,
    failures: MutableSet<CaptureFailure>,
  ) {
    if (!observation.isNumber) {
      failures += CaptureFailure.NONFINITE_PRIMARY_OBSERVATION
      return
    }
    val doubleValue = observation.asDouble()
    if (!doubleValue.isFinite()) {
      failures += CaptureFailure.NONFINITE_PRIMARY_OBSERVATION
      return
    }
    val decimal = runCatching { observation.decimalValue() }.getOrNull()
    if (decimal == null || decimal <= BigDecimal.ZERO) {
      failures += CaptureFailure.NONPOSITIVE_PRIMARY_OBSERVATION
    }
  }

  private fun ObjectNode.integer(name: String): Int? =
    get(name)?.takeIf(JsonNode::isIntegralNumber)?.asInt()

  private fun ObjectNode.textOrNull(name: String): String? =
    get(name)?.takeIf(JsonNode::isTextual)?.asString()
}

internal data class ParsedJmhRow(val json: ObjectNode, val key: CellKey)
