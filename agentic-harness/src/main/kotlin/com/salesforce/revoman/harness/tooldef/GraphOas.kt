/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

/** The type of a graph input slot, used to validate LLM-filled arguments before execution. */
enum class SlotType {
  STRING,
  INT,
  ENUM,
}

/** Schema for one input slot: its type, allowed values (for [SlotType.ENUM]), and whether required. */
data class SlotSchema(
  val type: SlotType,
  val values: List<String> = emptyList(),
  val required: Boolean = true,
)

/**
 * A small, hand-authored OpenAPI-style spec for one graph. The [ToolDefGenerator] distils it — plus
 * the graph metadata — into the four LLM-facing tool-def fields. Never dumped raw into a prompt.
 */
data class GraphOas(
  val graph: String,
  val slots: Map<String, SlotSchema>,
  val exampleQueries: List<String>,
  val inputExamples: List<Map<String, String>>,
  val whenNotToUse: List<String> = emptyList(),
)
