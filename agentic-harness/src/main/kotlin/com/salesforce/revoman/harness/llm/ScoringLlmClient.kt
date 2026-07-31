/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.llm

import com.salesforce.revoman.harness.tooldef.ToolDef

/**
 * A deterministic, calibration-aware router. Scores each tool by keyword overlap between the
 * utterance and the tool's `when_to_use` + `example_queries`, then subtracts a penalty for every
 * `when_not_to_use` clause whose trigger words appear in the utterance. Unlike [StubLlmClient]'s
 * fixed keyword map, this client's routing genuinely CHANGES when a `when_not_to_use` clause is
 * added — which is what makes Stage 3's confusion-matrix calibration loop a real experiment, not a
 * staged one. Slot-filling is delegated (calibration is about selection, not extraction).
 */
class ScoringLlmClient(private val slotDelegate: LlmClient = StubLlmClient()) : LlmClient {
  private val stopwords =
    setOf(
      "the", "for", "this", "that", "with", "into", "from", "and", "you", "your", "does", "what",
      "are", "our", "its", "new", "saved",
    )
  private val penaltyPerClause = 5

  override fun route(utterance: String, tools: List<ToolDef>): RouteDecision {
    val tokens = tokenize(utterance)
    val best = tools.map { it to score(tokens, it) }.maxByOrNull { it.second }
    return if (best == null || best.second <= 0) {
      RouteDecision(null, "no graph scored above zero for: $utterance")
    } else {
      RouteDecision(best.first.graphName, "score=${best.second} for '${best.first.graphName}'")
    }
  }

  override fun fillSlots(utterance: String, tool: ToolDef): Map<String, String> =
    slotDelegate.fillSlots(utterance, tool)

  private fun score(tokens: List<String>, tool: ToolDef): Int {
    val haystack =
      (tool.graphName + " " + tool.whenToUse + " " + tool.exampleQueries.joinToString(" "))
        .lowercase()
    val positive = tokens.count { token -> haystack.contains(token) }
    val utteranceTokens = tokens.toSet()
    val penalty =
      tool.whenNotToUse.count { clause ->
        val triggers = tokenize(clause).filter { it.length >= 4 }
        val matchCount = triggers.count { trigger -> trigger in utteranceTokens }
        matchCount >= 2  // Require at least 2 matching triggers to apply the penalty
      } * penaltyPerClause
    return positive - penalty
  }

  private fun tokenize(text: String): List<String> =
    text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stopwords }
}
