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
import com.salesforce.revoman.harness.tooldef.GraphMetadataParser
import com.salesforce.revoman.harness.tooldef.GraphOasLoader
import com.salesforce.revoman.harness.tooldef.GraphSpec
import com.salesforce.revoman.harness.tooldef.ToolDefGenerator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ContractFidelityCheckTest {
  private lateinit var server: MockCpqServer
  private lateinit var baseUrl: String
  private val check = ContractFidelityCheck()

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
  fun `a graph whose runtime matches its declared metadata is faithful`() {
    val spec = GraphMetadataParser.parse("configure")
    val report = check.check(configureContract(), spec)
    assertThat(report.faithful).isTrue()
    assertThat(report.missingProduced).isEmpty()
    assertThat(report.unexpectedProduced).isEmpty()
  }

  @Test
  fun `a declared output the run never produced is caught as drift`() {
    // Simulate metadata drift: the spec claims an extra output 'discountId' the graph never sets.
    val driftedSpec =
      GraphMetadataParser.parse("configure").let {
        GraphSpec(it.name, it.description, it.slots, it.outputKeys + "discountId")
      }
    val report = check.check(configureContract(), driftedSpec)
    assertThat(report.faithful).isFalse()
    assertThat(report.missingProduced).contains("discountId")
  }
}
