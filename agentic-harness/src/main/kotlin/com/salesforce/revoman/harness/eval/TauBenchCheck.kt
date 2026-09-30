/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.Orchestrator
import com.salesforce.revoman.harness.tooldef.ToolDef

/** A task-success case: an utterance and the final DB values it should produce. */
data class TaskCase(val utterance: String, val expectedDbValues: List<String>)

/**
 * tau-bench-style check: grade the final database STATE, not the chat transcript. Orchestrates the
 * utterance end to end and asserts the mock CPQ DB's values contain every expected value.
 */
class TauBenchCheck(
  private val baseUrl: String,
  private val tools: List<ToolDef>,
  private val llm: LlmClient,
) {
  fun check(server: MockCpqServer, case: TaskCase): Boolean {
    Orchestrator(baseUrl, tools, llm).orchestrate(case.utterance)
    val actual = server.db.values.map { it.toString() }
    return case.expectedDbValues.all { expected -> actual.contains(expected) }
  }
}
