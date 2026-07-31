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
import com.salesforce.revoman.output.StopReason
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The design's "evals Layer 1" beat: deterministic API-graph contract tests, no LLM. Runs the
 * graph chain against the mock and asserts on the Rundown — the exact same engine that will run
 * graphs at agent runtime.
 */
class Layer1ContractTest {
  private lateinit var server: MockCpqServer
  private var baseUrl: String = ""

  @BeforeEach
  fun setUp() {
    server = MockCpqServer()
    baseUrl = "http://127.0.0.1:${server.start()}"
  }

  @AfterEach fun tearDown() = server.stop()

  @Test
  fun `every graph in the chain completes with no unsuccessful step`() {
    val rundowns = GraphRunner.runChain(baseUrl)
    rundowns.forEach { rundown ->
      assertThat(rundown.areAllStepsSuccessful).isTrue()
      assertThat(rundown.firstUnIgnoredUnsuccessfulStepReport).isNull()
      assertThat(rundown.stopReason).isEqualTo(StopReason.COMPLETED)
    }
  }
}
