/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.orchestrator

import com.salesforce.revoman.harness.tooldef.GraphMetadataParser
import com.salesforce.revoman.harness.tooldef.GraphOasLoader
import com.salesforce.revoman.harness.tooldef.ToolDef
import com.salesforce.revoman.harness.tooldef.ToolDefGenerator

/**
 * The static graph registry (design decision: static tool list at v1). Assembles the LLM-facing
 * tool definitions for every graph from its metadata + OAS. Retrieval-based selection drops in here
 * later without changing consumers.
 */
object GraphRegistry {
  val GRAPHS: List<String> = listOf("configure", "price", "quote")

  fun loadToolDefs(): List<ToolDef> =
    GRAPHS.map { ToolDefGenerator.generate(GraphMetadataParser.parse(it), GraphOasLoader.load(it)) }
}
