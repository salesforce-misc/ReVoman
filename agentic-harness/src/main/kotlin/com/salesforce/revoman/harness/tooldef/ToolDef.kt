/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

/**
 * The LLM-facing definition of one graph tool: the four calibration fields from the design
 * (`when_to_use`, `when_not_to_use`, `example_queries`, `input_examples`) plus the slot schema the
 * slot-filler validates against before execution.
 */
data class ToolDef(
  val graphName: String,
  val whenToUse: String,
  val whenNotToUse: List<String>,
  val exampleQueries: List<String>,
  val inputExamples: List<Map<String, String>>,
  val slots: Map<String, SlotSchema>,
)
