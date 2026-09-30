/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.harness.eval.RouterEvaluator
import com.salesforce.revoman.harness.eval.EvalSet
import com.salesforce.revoman.harness.feedback.ConfirmGate
import com.salesforce.revoman.harness.feedback.Decision
import com.salesforce.revoman.harness.feedback.FeedbackLabel
import com.salesforce.revoman.harness.feedback.NightlyBatch
import com.salesforce.revoman.harness.feedback.Proposal
import com.salesforce.revoman.harness.llm.ScoringLlmClient
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.orchestrator.Orchestrator
import com.salesforce.revoman.harness.orchestrator.OrchestrationResult
import com.salesforce.revoman.harness.telemetry.GenAiTracer

/**
 * Stage 4 runnable demo: observability + the feedback flywheel, all deterministic (no API key).
 * (1) Traces turns with OpenTelemetry GenAI-convention spans printed to console. (2) Runs each
 * executed proposal through the HITL confirm gate (a scripted confirm + a reject of the near-miss).
 * (3) Runs the nightly batch to grow the eval set and draft a `when_not_to_use` clause. (4) Closes
 * the loop: re-evaluates the router on the grown set, showing the CI gate tighten.
 */
fun main() {
  val tools = GraphRegistry.loadToolDefs()
  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  val tracer = GenAiTracer()
  val orchestrator = Orchestrator(baseUrl, tools, StubLlmClient(), tracer)

  try {
    println("=== 1. Traced orchestration turns (OTel GenAI spans to console) ===")
    val executed = orchestrator.orchestrate("configure 2 units of SKU-1")

    println("\n=== 2. HITL confirm gate ===")
    val labels = mutableListOf<FeedbackLabel>()
    if (executed is OrchestrationResult.Executed) {
      val proposal = Proposal("configure 2 units of SKU-1", executed.graph, executed.slots)
      val label = ConfirmGate.apply(proposal, Decision.Confirm)
      labels.add(label)
      println("  confirm -> $label")
    }
    // A human rejects the near-miss the router would get wrong, stating the correct graph.
    val rejectProposal = Proposal("quote me a price for this config", "quote", emptyMap())
    val rejectLabel = ConfirmGate.apply(rejectProposal, Decision.Reject(correctGraph = "price"))
    labels.add(rejectLabel)
    println("  reject  -> $rejectLabel")

    println("\n=== 3. Nightly batch (mine labels -> grow eval set + draft when_not_to_use) ===")
    // For the demo, use a seed eval set that excludes the near-miss case, so we can show growth.
    val fullEval = EvalSet.load()
    val seedEval = fullEval.filter { it.utterance != "quote me a price for this config" }
    val batch = NightlyBatch.run(seedEval, labels)
    println("  eval set grew: ${seedEval.size} -> ${batch.grownEvalSet.size} cases")
    batch.draftedClauses.forEach { (graph, clauses) ->
      clauses.forEach { println("  drafted when_not_to_use for '$graph': $it") }
    }

    println("\n=== 4. Close the loop: re-eval shows the CI gate tighten ===")
    val evaluator = RouterEvaluator(ScoringLlmClient())
    val seedTools = tools.map { it.copy(whenNotToUse = emptyList()) }
    val calibratedTools =
      seedTools.map {
        if (batch.draftedClauses.containsKey(it.graphName))
          it.copy(whenNotToUse = batch.draftedClauses[it.graphName]!!)
        else it
      }
    val before = evaluator.evaluate(batch.grownEvalSet, seedTools)
    val after = evaluator.evaluate(batch.grownEvalSet, calibratedTools)
    println("  accuracy on grown eval set: ${before.matrix.correct}/${before.matrix.total} -> " +
      "${after.matrix.correct}/${after.matrix.total}  (auto-drafted from a rejected turn)")
  } finally {
    server.stop()
  }
}
