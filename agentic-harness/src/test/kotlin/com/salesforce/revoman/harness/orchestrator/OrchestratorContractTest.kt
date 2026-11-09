/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.orchestrator

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.contract.toJson
import com.salesforce.revoman.harness.llm.StubLlmClient
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.output.Verbosity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OrchestratorContractTest {
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
  fun `an executed turn carries a unified GraphContract`() {
    val result =
      Orchestrator(baseUrl, tools, StubLlmClient()).orchestrate("configure 2 units of SKU-1")
    val executed = result as OrchestrationResult.Executed

    assertThat(executed.contract).isNotNull()
    val contract = executed.contract!!
    assertThat(contract.descriptor.graphName).isEqualTo("configure")
    assertThat(contract.invocationSlots).containsEntry("quantity", "2")
    assertThat(contract.succeeded).isTrue()

    // The unified contract renders both halves + provenance.
    val json = contract.toJson(Verbosity.STANDARD)
    assertThat(json).contains("\"whenToUse\"")
    assertThat(json).contains("\"dataFlow\"")
    assertThat(json).contains("configId")
  }
}
