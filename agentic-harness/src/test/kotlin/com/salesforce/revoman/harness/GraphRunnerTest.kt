/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.harness.mock.MockCpqServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GraphRunnerTest {
  private lateinit var server: MockCpqServer
  private var baseUrl: String = ""

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    baseUrl = "http://127.0.0.1:${server.start()}"
  }

  @AfterEach fun tearDown() = server.stop()

  @Test
  fun `configure-price-quote chain threads ids forward and lands a draft quote`() {
    val rundowns = GraphRunner.runChain(baseUrl)

    // Three graphs ran, each with no failing step.
    assertThat(rundowns).hasSize(3)
    rundowns.forEach { assertThat(it.firstUnIgnoredUnsuccessfulStepReport).isNull() }

    // The final env carries the threaded ids — proof {{var}} edges connected the graphs.
    val finalEnv = rundowns.last().mutableEnv
    assertThat(finalEnv.getAsString("configId")).startsWith("cfg-")
    assertThat(finalEnv.getAsString("priceId")).startsWith("prc-")
    assertThat(finalEnv.getAsString("quoteId")).startsWith("qot-")

    // The mock "DB" recorded a DRAFT quote (tau-bench-style state proof, previewed here).
    assertThat(server.db.values).contains("DRAFT")
  }
}
