/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.orchestrator

import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.tooldef.SlotType
import com.salesforce.revoman.harness.tooldef.ToolDef

/** The result of slot-filling: validated arguments, or the list of validation failures. */
sealed interface FillResult {
  data class Valid(val slots: Map<String, String>) : FillResult

  data class Invalid(val errors: List<String>) : FillResult
}

/**
 * Prompt B: fills a graph's input placeholders via the [LlmClient], then validates every argument
 * against the graph's typed schema BEFORE it can reach ReVoman. Hallucinated argument names,
 * out-of-enum values, non-integer numbers, and missing required slots are all rejected at the door.
 */
class SlotFiller(private val llm: LlmClient) {
  fun fill(utterance: String, tool: ToolDef): FillResult {
    val filled = llm.fillSlots(utterance, tool)
    val errors = buildList {
      // Reject hallucinated slot names not declared by the graph.
      (filled.keys - tool.slots.keys).forEach { add("unknown slot '$it' (not declared by graph '${tool.graphName}')") }
      // Validate each declared slot.
      tool.slots.forEach { (name, schema) ->
        val value = filled[name]
        when {
          value == null -> if (schema.required) add("missing required slot '$name'")
          schema.type == SlotType.INT && value.toIntOrNull() == null ->
            add("slot '$name' expects an integer but got '$value'")
          schema.type == SlotType.ENUM && value !in schema.values ->
            add("slot '$name' value '$value' is not one of ${schema.values}")
        }
      }
    }
    return if (errors.isEmpty()) FillResult.Valid(filled) else FillResult.Invalid(errors)
  }
}
