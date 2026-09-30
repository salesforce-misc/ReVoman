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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TauBenchCheckTest {
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
  fun `configure task reaches the expected final DB state`() {
    val check = TauBenchCheck(baseUrl, tools, StubLlmClient())
    val passed = check.check(server, TaskCase("configure 2 units of SKU-1", listOf("SKU-1 x2")))
    assertThat(passed).isTrue()
  }

  @Test
  fun `an unmet final state fails the task`() {
    val check = TauBenchCheck(baseUrl, tools, StubLlmClient())
    val passed =
      check.check(server, TaskCase("configure 2 units of SKU-1", listOf("SKU-9 x99")))
    assertThat(passed).isFalse()
  }
}
