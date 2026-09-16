/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

/**
 * Structural metadata distilled from a ReVoman V3 graph collection — the raw material the
 * [ToolDefGenerator] turns into an LLM-facing tool definition.
 *
 * @property slots the input `{{placeholder}}` names the LLM must fill (infra + intra-graph outputs
 *   excluded — those are threaded deterministically by ReVoman, never by the LLM).
 * @property outputKeys the `pm.environment.set(...)` keys the graph produces (its internal edges).
 */
data class GraphSpec(
  val name: String,
  val description: String,
  val slots: List<String>,
  val outputKeys: List<String>,
)
