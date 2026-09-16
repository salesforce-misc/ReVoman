/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.GraphRunner
import com.salesforce.revoman.harness.mock.MockCpqServer
import com.salesforce.revoman.harness.orchestrator.GraphRegistry
import com.salesforce.revoman.harness.tooldef.GraphMetadataParser
import com.salesforce.revoman.harness.tooldef.GraphOasLoader
import com.salesforce.revoman.harness.tooldef.ToolDefGenerator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GraphContractTest {
  private lateinit var server: MockCpqServer
  private lateinit var baseUrl: String

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    baseUrl = "http://127.0.0.1:${server.start()}"
  }

  @AfterEach fun tearDown() = server.stop()

  private fun configureContract(): GraphContract {
    val descriptor =
      ToolDefGenerator.generate(GraphMetadataParser.parse("configure"), GraphOasLoader.load("configure"))
    val slots = mapOf("productCode" to "SKU-1", "quantity" to "2")
    val rundowns = GraphRunner.runChain(baseUrl, listOf("configure"), seedEnv = slots)
    return GraphContract.of(descriptor, slots, rundowns.single())
  }

  @Test
  fun `fuses descriptor, invocation slots, and runtime outcome into one object`() {
    val contract = configureContract()
    assertThat(contract.descriptor.graphName).isEqualTo("configure")
    assertThat(contract.invocationSlots).containsEntry("productCode", "SKU-1")
    assertThat(contract.succeeded).isTrue()
    assertThat(contract.contractVersion).isEqualTo("1.0")
  }

  @Test
  fun `dataFlow exposes provenance — configId was produced by the configure step`() {
    val contract = configureContract()
    val configIdEdge = contract.dataFlow.firstOrNull { it.key == "configId" }
    assertThat(configIdEdge).isNotNull()
    assertThat(configIdEdge!!.producedByStep).isNotNull()
    // The producing step's name identifies where the value came from.
    assertThat(configIdEdge.producedByStep).contains("configure")
  }
}
