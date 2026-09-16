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
import com.salesforce.revoman.harness.tooldef.ToolDefGenerator
import com.salesforce.revoman.output.Verbosity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GraphContractWriterTest {
  private lateinit var server: MockCpqServer
  private lateinit var baseUrl: String

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    baseUrl = "http://127.0.0.1:${server.start()}"
  }

  @AfterEach fun tearDown() = server.stop()

  private fun contract(): GraphContract {
    val descriptor =
      ToolDefGenerator.generate(GraphMetadataParser.parse("configure"), GraphOasLoader.load("configure"))
    val slots = mapOf("productCode" to "SKU-1", "quantity" to "2")
    val rundowns = GraphRunner.runChain(baseUrl, listOf("configure"), seedEnv = slots)
    return GraphContract.of(descriptor, slots, rundowns.single())
  }

  @Test
  fun `summary carries version, graph, invocation slots, and outcome stats but not descriptions`() {
    val json = contract().toJson(Verbosity.SUMMARY)
    assertThat(json).contains("\"contractVersion\"")
    assertThat(json).contains("\"graph\"")
    assertThat(json).contains("configure")
    assertThat(json).contains("\"invocationSlots\"")
    assertThat(json).contains("\"stopReason\"")
    // SUMMARY omits the heavy descriptor prose and dataFlow.
    assertThat(json).doesNotContain("\"whenToUse\"")
    assertThat(json).doesNotContain("\"dataFlow\"")
  }

  @Test
  fun `standard adds descriptor whenToUse and the dataFlow provenance block`() {
    val json = contract().toJson(Verbosity.STANDARD)
    assertThat(json).contains("\"whenToUse\"")
    assertThat(json).contains("\"dataFlow\"")
    assertThat(json).contains("configId") // a produced key appears in the lineage
  }

  @Test
  fun `verbose embeds the full nested rundown json`() {
    val json = contract().toJson(Verbosity.VERBOSE)
    assertThat(json).contains("\"rundown\"")
    assertThat(json).contains("\"exampleQueries\"")
  }

  @Test
  fun `verbose output is syntactically valid parseable JSON`() {
    val json = contract().toJson(Verbosity.VERBOSE)
    // JSON is a subset of YAML; snakeyaml parses it and throws on malformed input.
    val parsed = org.yaml.snakeyaml.Yaml().load<Map<String, Any?>>(json)
    assertThat(parsed).isNotNull()
    assertThat(parsed["graph"]).isEqualTo("configure")
    assertThat(parsed["contractVersion"]).isEqualTo("1.0")
  }
}
