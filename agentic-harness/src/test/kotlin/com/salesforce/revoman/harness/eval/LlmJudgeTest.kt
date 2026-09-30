/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.orchestrator.Orchestrator
import com.salesforce.revoman.harness.orchestrator.OrchestrationResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LlmJudgeTest {
  private lateinit var server: MockCpqServer
  private lateinit var baseUrl: String
  private val tools = GraphRegistry.loadToolDefs()

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    baseUrl = "http://127.0.0.1:${server.start()}"
  }

  @AfterEach fun tearDown() = server.stop()

  @Test
  fun `judge screens a successful turn and ground truth confirms it`() {
    val result =
      Orchestrator(baseUrl, tools, StubLlmClient()).orchestrate("configure 2 units of SKU-1")
    val executed = result as OrchestrationResult.Executed

    val judgment = StubJudge().judge("configure 2 units of SKU-1", executed.context)
    assertThat(judgment.pass).isTrue()

    // The deterministic gate agrees — the judge is only a screen, not the source of truth.
    assertThat(GroundTruth.allStepsSucceeded(executed.rundowns)).isTrue()
  }

  @Test
  fun `judge fails an empty or unsuccessful context`() {
    assertThat(StubJudge().judge("x", "no useful context here").pass).isFalse()
  }
}
