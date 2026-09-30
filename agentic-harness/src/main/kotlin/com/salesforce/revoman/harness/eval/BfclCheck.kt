/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.orchestrator.FillResult
import com.salesforce.revoman.harness.orchestrator.Router
import com.salesforce.revoman.harness.orchestrator.SlotFiller
import com.salesforce.revoman.harness.tooldef.ToolDef
import org.yaml.snakeyaml.Yaml

/** BFCL-style gold: the exact graph + slot values an utterance should produce. */
data class SlotFillGold(val utterance: String, val graph: String, val slots: Map<String, String>)

/** Loads the BFCL gold set from a classpath YAML resource. */
object SlotFillGoldSet {
  fun load(resource: String = "evals/slotfill-gold.yaml"): List<SlotFillGold> {
    val text =
      javaClass.classLoader.getResourceAsStream(resource)?.bufferedReader()?.readText()
        ?: error("Slot-fill gold not found on classpath: $resource")
    @Suppress("UNCHECKED_CAST") val root = Yaml().load<Map<String, Any?>>(text)
    @Suppress("UNCHECKED_CAST") val cases = (root["cases"] as? List<Map<String, Any?>>).orEmpty()
    return cases.map { c ->
      @Suppress("UNCHECKED_CAST") val slots = (c["slots"] as? Map<String, Any?>).orEmpty()
      SlotFillGold(
        c["utterance"].toString(),
        c["graph"].toString(),
        slots.mapValues { it.value.toString() },
      )
    }
  }
}

/** The outcome of one BFCL comparison: predicted call vs gold, by value equality. */
data class BfclResult(
  val gold: SlotFillGold,
  val predictedGraph: String?,
  val predictedSlots: Map<String, String>,
  val graphMatch: Boolean,
  val slotsMatch: Boolean,
) {
  val pass: Boolean
    get() = graphMatch && slotsMatch
}

/**
 * BFCL-style check: run the router + slot-filler over an utterance and AST-compare the predicted
 * call (graph name + filled slots) against gold by value equality. Rejected (invalid) slot-fills
 * predict no slots — a clean fail, never a silent partial pass.
 */
class BfclCheck(private val llm: LlmClient, private val tools: List<ToolDef>) {
  private val router = Router(llm, tools)
  private val slotFiller = SlotFiller(llm)

  fun check(gold: SlotFillGold): BfclResult {
    val predictedGraph = router.route(gold.utterance).graphName
    val tool = tools.firstOrNull { it.graphName == predictedGraph }
    val predictedSlots =
      if (tool == null) emptyMap()
      else
        when (val fill = slotFiller.fill(gold.utterance, tool)) {
          is FillResult.Valid -> fill.slots
          is FillResult.Invalid -> emptyMap()
        }
    val graphMatch = predictedGraph == gold.graph
    val slotsMatch = predictedSlots == gold.slots
    return BfclResult(gold, predictedGraph, predictedSlots, graphMatch, slotsMatch)
  }
}
