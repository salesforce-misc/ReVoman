/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.retrieval

import com.salesforce.revoman.harness.tooldef.ToolDef
import kotlin.math.sqrt

/**
 * Scaffold 4: narrows many graphs to the top-K relevant candidates BEFORE the router reasons, so
 * the model reasons over a small, clean set (the reliability control from `reasoning-layer.md`).
 * The embedding is a local bag-of-tokens cosine stub — enough to prove the seam; a real vector
 * store drops in behind the same `topK` signature. A no-op at 3 graphs (returns all), it
 * demonstrably narrows a larger set.
 */
object RetrievalPreFilter {
  fun topK(intent: String, tools: List<ToolDef>, k: Int): List<ToolDef> {
    if (k >= tools.size) return tools
    val q = vector(intent)
    return tools
      .withIndex()
      .sortedWith(
        compareByDescending<IndexedValue<ToolDef>> { cosine(q, vector(docText(it.value))) }
          .thenBy { it.index }
      )
      .take(k)
      .map { it.value }
  }

  private fun docText(tool: ToolDef): String =
    tool.whenToUse + " " + tool.exampleQueries.joinToString(" ")

  private fun vector(text: String): Map<String, Int> =
    tokenize(text).groupingBy { it }.eachCount()

  private fun cosine(a: Map<String, Int>, b: Map<String, Int>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val dot = a.keys.intersect(b.keys).sumOf { a.getValue(it) * b.getValue(it) }
    val magA = sqrt(a.values.sumOf { it * it }.toDouble())
    val magB = sqrt(b.values.sumOf { it * it }.toDouble())
    return if (magA == 0.0 || magB == 0.0) 0.0 else dot / (magA * magB)
  }

  private fun tokenize(text: String): List<String> =
    text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
}
