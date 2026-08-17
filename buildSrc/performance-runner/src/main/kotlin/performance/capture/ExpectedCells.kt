/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import performance.json.CanonicalJson
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** One immutable exact JMH row identity declared by the frozen protocol. */
class ExpectedCell(benchmark: String, parameters: Map<String, String>) {
  val benchmark: String = benchmark
  val parameters: Map<String, String> = Collections.unmodifiableMap(parameters.toSortedMap())

  init {
    require(BENCHMARK.matches(benchmark)) { "invalid benchmark identity" }
    require(this.parameters.keys.all(SAFE_PARAMETER::matches)) { "invalid parameter name" }
    require(
      this.parameters.values.all {
        it.length in 1..128 && ',' !in it && '\n' !in it && '\r' !in it
      },
    ) {
      "invalid parameter value"
    }
  }

  internal val key: CellKey = CellKey(benchmark, this.parameters)

  override fun equals(other: Any?): Boolean =
    other is ExpectedCell && benchmark == other.benchmark && parameters == other.parameters

  override fun hashCode(): Int = 31 * benchmark.hashCode() + parameters.hashCode()

  override fun toString(): String = "ExpectedCell(benchmark=$benchmark, parameters=$parameters)"

  private companion object {
    val BENCHMARK = Regex("[A-Za-z_$][A-Za-z0-9_$.]*\\.[A-Za-z_$][A-Za-z0-9_$]*")
    val SAFE_PARAMETER = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
  }
}

/** The immutable nonempty exact row set for one selected profile family. */
class ExpectedCells(cells: List<ExpectedCell>) {
  val cells: List<ExpectedCell> = Collections.unmodifiableList(cells.toList())

  init {
    require(this.cells.isNotEmpty()) { "expected cells must not be empty" }
    require(this.cells.map(ExpectedCell::key).distinct().size == this.cells.size) {
      "expected cells must be unique"
    }
  }

  internal val keys: Set<CellKey> =
    Collections.unmodifiableSet(this.cells.mapTo(linkedSetOf()) { it.key })

  /** Whether one JMH CLI invocation can select this exact matrix without adding combinations. */
  internal fun isJmhCliRepresentable(): Boolean {
    val globalValues =
      cells
        .flatMap { cell -> cell.parameters.map { (name, value) -> name to value } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.toSet() }
    return cells.groupBy(ExpectedCell::benchmark).values.all { benchmarkCells ->
      val parameterNames = benchmarkCells.first().parameters.keys
      if (benchmarkCells.any { it.parameters.keys != parameterNames }) return@all false
      var combinationCount = 1L
      parameterNames.forEach { name ->
        val valueCount = globalValues.getValue(name).size.toLong()
        if (combinationCount > benchmarkCells.size.toLong() / valueCount) return@all false
        combinationCount *= valueCount
      }
      combinationCount == benchmarkCells.size.toLong()
    }
  }

  override fun equals(other: Any?): Boolean = other is ExpectedCells && cells == other.cells

  override fun hashCode(): Int = cells.hashCode()

  override fun toString(): String = "ExpectedCells(cells=$cells)"
}

internal data class CellKey(val benchmark: String, val parameters: Map<String, String>)

/** Strict reader for the checked-in expected-cell declaration. */
object ExpectedCellsReader {
  fun read(path: Path, family: CaptureProfileFamily): ExpectedCells =
    read(Files.readAllBytes(path), family)

  fun read(bytes: ByteArray, family: CaptureProfileFamily): ExpectedCells {
    val document = CanonicalJson.parseStrict(bytes) as? ObjectNode ?: error("expected-cells root")
    require(
      EvidenceSchemaValidator()
        .validate(SchemaKind.EXPECTED_CELLS, CanonicalJson.encode(document))
        .isEmpty(),
    ) {
      "expected-cells schema mismatch"
    }
    require(document.properties().map { it.key }.toSet() == ROOT_FIELDS) {
      "unexpected expected-cells field"
    }
    val families = document.get("families") as ObjectNode
    val rows = families.get(family.id) as ArrayNode
    return ExpectedCells(
      rows.values().asSequence().map { row -> parseCell(row as ObjectNode) }.toList(),
    )
  }

  private fun parseCell(row: ObjectNode): ExpectedCell {
    require(row.properties().map { it.key }.toSet() == CELL_FIELDS) {
      "unexpected expected-cell field"
    }
    val parameters =
      (row.get("parameters") as ObjectNode)
        .properties()
        .associate { (name, value) -> name to value.asString() }
    return ExpectedCell(row.get("benchmark").asString(), parameters)
  }

  private val ROOT_FIELDS = setOf("\$schema", "schemaVersion", "families")
  private val CELL_FIELDS = setOf("benchmark", "parameters")
}
