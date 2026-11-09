/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.harness.mock.MockCpqServer

/**
 * Stage 1 runnable demo: boot the mock CPQ server, run the configure->price->quote graph chain
 * through ReVoman, and print each Rundown summary. This is the deterministic worker, end to end,
 * with no LLM involved.
 */
fun main() {
  val server = MockCpqServer()
  val baseUrl = "http://127.0.0.1:${server.start()}"
  try {
    println("Mock CPQ server up at $baseUrl")
    println(GraphRunner.runChainAndSummarize(baseUrl))
    println("Final mock DB state: ${server.db}")
  } finally {
    server.stop()
  }
}
