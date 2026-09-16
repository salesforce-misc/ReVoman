/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.salesforce.revoman.harness.tooldef.ToolDef
import com.salesforce.revoman.output.Rundown

/**
 * The blue-box unified contract: the single deterministic artifact that feeds the LLM, fusing the
 * two halves that otherwise live apart —
 *  - the pre-execution METADATA descriptor ([descriptor], a [ToolDef]: when_to_use / when_not_to_use
 *    / example_queries / input_examples + typed slots), and
 *  - the post-execution RUNTIME information ([outcome], a ReVoman [Rundown]: steps, stopReason,
 *    per-step request/response, env) —
 * plus [invocationSlots] (what the graph was actually called with) and [dataFlow] (provenance: which
 * `{{var}}` came from which step). A [contractVersion] stamps the schema so consumers detect drift.
 *
 * Deterministic and testable like ordinary software — the ReVoman-owned half of the system. The
 * probabilistic reasoning that CONSUMES this contract (router / confidence gate) is a separate layer.
 */
data class GraphContract(
  val descriptor: ToolDef,
  val invocationSlots: Map<String, String>,
  val outcome: Rundown,
  val contractVersion: String = CONTRACT_VERSION,
) {
  val succeeded: Boolean
    get() = outcome.areAllStepsSuccessful

  /** Per-env-key provenance derived from each step's produced/consumed sets. */
  val dataFlow: List<DataFlowEdge> by lazy {
    val keys =
      outcome.stepReports.flatMap { it.envVars.produced + it.envVars.consumed }.toSortedSet()
    keys.map { key ->
      val producer = outcome.stepReports.firstOrNull { key in it.envVars.produced }?.step?.name
      val consumers =
        outcome.stepReports.filter { key in it.envVars.consumed }.map { it.step.name }.distinct()
      DataFlowEdge(key, producer, consumers)
    }
  }

  companion object {
    const val CONTRACT_VERSION: String = "1.0"

    fun of(descriptor: ToolDef, invocationSlots: Map<String, String>, outcome: Rundown): GraphContract =
      GraphContract(descriptor, invocationSlots, outcome)
  }
}
