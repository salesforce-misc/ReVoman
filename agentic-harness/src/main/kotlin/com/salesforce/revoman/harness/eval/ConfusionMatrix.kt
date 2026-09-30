/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

/**
 * A graph-selection confusion matrix: `counts[expected][predicted]` tallies how often the router
 * predicted each graph for each gold label. The diagonal is correct; every off-diagonal cell is a
 * confusion that Stage 3's calibration loop turns into a `when_not_to_use` clause.
 */
data class ConfusionMatrix(
  val rowLabels: List<String>,
  val colLabels: List<String>,
  val counts: Map<String, Map<String, Int>>,
) {
  val total: Int
    get() = counts.values.sumOf { row -> row.values.sum() }

  val correct: Int
    get() = rowLabels.sumOf { label -> counts[label]?.get(label) ?: 0 }

  val accuracy: Double
    get() = if (total == 0) 0.0 else correct.toDouble() / total

  /** Renders an aligned text grid: rows are gold labels, columns are predictions. */
  fun render(): String {
    val width = (colLabels + rowLabels).maxOf { it.length } + 2
    val header = "gold\\pred".padEnd(width) + colLabels.joinToString("") { it.padStart(width) }
    val rows =
      rowLabels.joinToString("\n") { row ->
        row.padEnd(width) +
          colLabels.joinToString("") { col -> (counts[row]?.get(col) ?: 0).toString().padStart(width) }
      }
    return "$header\n$rows\naccuracy: $correct/$total"
  }
}

/** Builds a [ConfusionMatrix] from `(expected, predicted)` pairs; a null prediction becomes "none". */
object ConfusionMatrices {
  private const val NONE = "none"

  fun from(pairs: List<Pair<String, String?>>): ConfusionMatrix {
    val rowLabels = pairs.map { it.first }.distinct().sorted()
    val predicted = pairs.map { it.second ?: NONE }.distinct()
    val colLabels = (rowLabels + predicted).distinct()
    val counts =
      rowLabels.associateWith { row ->
        colLabels.associateWith { col ->
          pairs.count { it.first == row && (it.second ?: NONE) == col }
        }
      }
    return ConfusionMatrix(rowLabels, colLabels, counts)
  }
}
