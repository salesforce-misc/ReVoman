/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.llm

import com.salesforce.revoman.harness.tooldef.SlotType
import com.salesforce.revoman.harness.tooldef.ToolDef

/**
 * A deterministic, network-free [LlmClient] for tests, CI, and the no-key demo. Routing is keyword
 * matching; slot-filling is regex extraction. It stands in for a real model everywhere the key is
 * absent, so the entire orchestrator is exercisable without an LLM.
 */
class StubLlmClient : LlmClient {
  private val routingKeywords: Map<String, List<String>> =
    mapOf(
      "configure" to listOf("configure", "product", "set up", "add "),
      "price" to listOf("price", "cost", "total", "how much"),
      "quote" to listOf("quote", "draft"),
    )
  private val skuRegex = Regex("""SKU-\d+""")
  private val configIdRegex = Regex("""cfg-\w+""")
  private val priceIdRegex = Regex("""prc-\w+""")
  private val intRegex = Regex("""\b(\d+)\b""")

  override fun route(utterance: String, tools: List<ToolDef>): RouteDecision {
    val lower = utterance.lowercase()
    val match =
      tools.firstOrNull { tool ->
        routingKeywords[tool.graphName].orEmpty().any { lower.contains(it) }
      }
    return match?.let { RouteDecision(it.graphName, "keyword match on '${it.graphName}'") }
      ?: RouteDecision(null, "no keyword matched any graph")
  }

  override fun fillSlots(utterance: String, tool: ToolDef): Map<String, String> =
    tool.slots.keys
      .mapNotNull { slot -> extract(slot, tool.slots[slot]!!.type, utterance)?.let { slot to it } }
      .toMap()

  private fun extract(slot: String, type: SlotType, utterance: String): String? =
    when {
      slot == "productCode" -> skuRegex.find(utterance)?.value
      slot == "configId" -> configIdRegex.find(utterance)?.value
      slot == "priceId" -> priceIdRegex.find(utterance)?.value
      type == SlotType.INT -> intRegex.find(utterance)?.groupValues?.get(1)
      else -> null
    }
}
