/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.salesforce.revoman.harness.tooldef.GraphSpec

/** Whether a contract's runtime data-flow matches what its metadata declared, and how it drifted. */
data class FidelityReport(
  val graph: String,
  val missingProduced: Set<String>,
  val unexpectedProduced: Set<String>,
) {
  val faithful: Boolean
    get() = missingProduced.isEmpty() && unexpectedProduced.isEmpty()
}

/**
 * The blue-box Layer-1 eval: deterministic, no LLM. Compares what the graph's metadata DECLARES it
 * produces ([GraphSpec.outputKeys]) against what the run ACTUALLY produced (the contract's data-flow
 * producers). Any mismatch is API drift — the production contract changing under the graph — caught
 * in CI before an agent ever reasons over a stale descriptor.
 */
class ContractFidelityCheck {
  fun check(contract: GraphContract, spec: GraphSpec): FidelityReport {
    val declared = spec.outputKeys.toSet()
    val actual = contract.dataFlow.filter { it.producedByStep != null }.map { it.key }.toSet()
    return FidelityReport(
      graph = spec.name,
      missingProduced = declared - actual,
      unexpectedProduced = actual - declared,
    )
  }
}
