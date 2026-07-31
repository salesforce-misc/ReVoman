/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

/**
 * Pure function: distils graph metadata + a small OAS into the four-field [ToolDef]. Reconciles the
 * two sources — the OAS must declare a schema for exactly the graph's input slots (no more, no
 * less) — so a graph and its spec cannot silently drift apart.
 */
object ToolDefGenerator {
  fun generate(spec: GraphSpec, oas: GraphOas): ToolDef {
    val metadataSlots = spec.slots.toSet()
    val oasSlots = oas.slots.keys
    require(metadataSlots == oasSlots) {
      val missing = metadataSlots - oasSlots
      val extra = oasSlots - metadataSlots
      "OAS for '${spec.name}' does not match graph slots. missing schema for=$missing, unknown slots=$extra"
    }
    return ToolDef(
      graphName = spec.name,
      whenToUse = spec.description,
      whenNotToUse = oas.whenNotToUse,
      exampleQueries = oas.exampleQueries,
      inputExamples = oas.inputExamples,
      slots = oas.slots,
    )
  }
}
