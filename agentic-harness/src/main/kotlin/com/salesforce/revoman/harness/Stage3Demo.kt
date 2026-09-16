/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.harness.eval.BfclCheck
import com.salesforce.revoman.harness.eval.EvalSet
import com.salesforce.revoman.harness.eval.GroundTruth
import com.salesforce.revoman.harness.eval.RouterEvaluator
import com.salesforce.revoman.harness.eval.SlotFillGoldSet
import com.salesforce.revoman.harness.eval.StubJudge
import com.salesforce.revoman.harness.eval.TaskCase
import com.salesforce.revoman.harness.eval.TauBenchCheck
import com.salesforce.revoman.harness.llm.ScoringLlmClient
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.orchestrator.Orchestrator
import com.salesforce.revoman.harness.orchestrator.OrchestrationResult

/**
 * Stage 3 runnable demo: evals + calibration in action, entirely deterministic (no API key). Shows
 * (1) the confusion matrix before/after adding a `when_not_to_use` clause and the accuracy move,
 * (2) BFCL slot-fill checks vs gold, (3) a tau-bench final-DB-state check, (4) an LLM-as-judge
 * screening a fuzzy metric alongside the deterministic ground-truth gate.
 */
fun main() {
  val cases = EvalSet.load()
  val evaluator = RouterEvaluator(ScoringLlmClient())
  val seedTools = GraphRegistry.loadToolDefs().map { it.copy(whenNotToUse = emptyList()) }
  val clause = "Do not use when the user asks for a price or cost — that is the price graph."
  val calibratedTools =
    seedTools.map { if (it.graphName == "quote") it.copy(whenNotToUse = listOf(clause)) else it }

  println("=== 1. Graph-selection confusion matrix (SEED, un-calibrated) ===")
  val seed = evaluator.evaluate(cases, seedTools)
  println(seed.matrix.render())
  println("misses: ${seed.misses}")

  println("\n=== 2. Calibration: add when_not_to_use to 'quote' ===")
  println("  + $clause")
  val calibrated = evaluator.evaluate(cases, calibratedTools)
  println(calibrated.matrix.render())
  println(
    "accuracy moved: ${seed.matrix.correct}/${seed.matrix.total} -> " +
      "${calibrated.matrix.correct}/${calibrated.matrix.total}"
  )

  println("\n=== 3. BFCL-style slot-fill check (predicted call vs gold) ===")
  val tools = GraphRegistry.loadToolDefs()
  val bfcl = BfclCheck(StubLlmClient(), tools)
  SlotFillGoldSet.load().forEach { gold ->
    val r = bfcl.check(gold)
    println("  ${if (r.pass) "PASS" else "FAIL"}  '${gold.utterance}' -> ${r.predictedGraph} ${r.predictedSlots}")
  }

  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  try {
    println("\n=== 4. tau-bench-style task success (final DB state) ===")
    val tau = TauBenchCheck(baseUrl, tools, StubLlmClient())
    val taskCase = TaskCase("configure 2 units of SKU-1", listOf("SKU-1 x2"))
    println("  ${if (tau.check(server, taskCase)) "PASS" else "FAIL"}  final DB = ${server.db}")

    println("\n=== 5. LLM-as-judge (screen) alongside deterministic ground-truth (gate) ===")
    when (val result = Orchestrator(baseUrl, tools, StubLlmClient()).orchestrate("configure 2 units of SKU-1")) {
      is OrchestrationResult.Executed -> {
        val judgment = StubJudge().judge("configure 2 units of SKU-1", result.context)
        println("  judge (advisory screen): pass=${judgment.pass} — ${judgment.reason}")
        println("  ground-truth (gate):     pass=${GroundTruth.allStepsSucceeded(result.rundowns)}")
      }
      else -> println("  (unexpected: $result)")
    }
  } finally {
    server.stop()
  }
}
