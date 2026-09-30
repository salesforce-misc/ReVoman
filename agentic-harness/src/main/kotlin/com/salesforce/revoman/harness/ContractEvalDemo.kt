/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.harness.GraphRunner
import com.salesforce.revoman.harness.contract.ContractAblationEval
import com.salesforce.revoman.harness.contract.ContractFidelityCheck
import com.salesforce.revoman.harness.contract.GraphContract
import com.salesforce.revoman.harness.eval.EvalSet
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.tooldef.GraphMetadataParser
import com.salesforce.revoman.harness.tooldef.GraphOasLoader
import com.salesforce.revoman.harness.tooldef.GraphSpec
import com.salesforce.revoman.harness.tooldef.ToolDefGenerator

/**
 * Blue-box eval demo: how we TEST and IMPROVE the unified contract, deterministic (no key).
 * (1) Fidelity — the contract's runtime matches its declared metadata (Layer-1, no LLM).
 * (2) Simulated drift — a declared output the run never produces is caught.
 * (3) Efficacy — enriching the contract raises graph-selection accuracy on the eval set.
 */
fun main() {
  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  try {
    val descriptor =
      ToolDefGenerator.generate(GraphMetadataParser.parse("configure"), GraphOasLoader.load("configure"))
    val slots = mapOf("productCode" to "SKU-1", "quantity" to "2")
    val contract =
      GraphContract.of(descriptor, slots, GraphRunner.runChain(baseUrl, listOf("configure"), slots).single())
    val check = ContractFidelityCheck()

    println("=== 1. Fidelity: does the contract mirror reality? (no LLM) ===")
    val spec = GraphMetadataParser.parse("configure")
    val ok = check.check(contract, spec)
    println("  configure faithful=${ok.faithful} (declared==actually-produced)")

    println("\n=== 2. Simulated API drift: metadata declares an output the run never produces ===")
    val drifted = GraphSpec(spec.name, spec.description, spec.slots, spec.outputKeys + "discountId")
    val drift = check.check(contract, drifted)
    println("  faithful=${drift.faithful}  missingProduced=${drift.missingProduced}  <-- drift caught in CI")

    println("\n=== 3. Efficacy: enrich the contract, measure accuracy on the eval set ===")
    val cases = EvalSet.load()
    val stripped = GraphRegistry.loadToolDefs().map { it.copy(whenNotToUse = emptyList()) }
    val enriched =
      stripped.map {
        if (it.graphName == "quote")
          it.copy(whenNotToUse = listOf("Do not use when the user asks for a price or cost — that is the price graph."))
        else it
      }
    val (a, b) = ContractAblationEval().compare(cases, "stripped" to stripped, "enriched" to enriched)
    println("  ${a.variantName}: ${a.accuracyCorrect}/${a.accuracyTotal}")
    println("  ${b.variantName}: ${b.accuracyCorrect}/${b.accuracyTotal}")
    println("  contract enrichment moved accuracy: ${a.accuracyCorrect}/${a.accuracyTotal} -> ${b.accuracyCorrect}/${b.accuracyTotal}")
  } finally {
    server.stop()
  }
}
