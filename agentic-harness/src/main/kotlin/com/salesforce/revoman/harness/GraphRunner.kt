/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.Verbosity
import com.salesforce.revoman.output.toJson

/**
 * The deterministic worker: runs one or more ReVoman V3 graph collections in order, threading the
 * mutable environment forward from each graph into the next (this is the {{var}} edge mechanism —
 * no LLM, no @{ref.id} operator). Wraps `ReVoman.revUp(List<Kick>)`.
 */
object GraphRunner {
  val DEFAULT_CHAIN: List<String> = listOf("configure", "price", "quote")

  fun runChain(
    baseUrl: String,
    graphs: List<String> = DEFAULT_CHAIN,
    seedEnv: Map<String, Any?> = emptyMap(),
  ): List<Rundown> {
    logger.log(System.Logger.Level.INFO, "Starting graph chain: baseUrl={0}, graphs={1}", baseUrl, graphs.joinToString(","))
    val runtimeEnv: Map<String, Any?> = mapOf("baseUrl" to baseUrl) + seedEnv
    val kicks =
      graphs.map { graph ->
        Kick.configure()
          .templatePath("graphs/$graph")
          .environmentPath("graphs/$graph/$graph.environment.yaml")
          .dynamicEnvironment(runtimeEnv)
          .off()
      }
    val rundowns = ReVoman.revUp(kicks)
    val summary = rundowns.joinToString(", ") { "${it.stopReason}" }
    logger.log(System.Logger.Level.INFO, "Graph chain completed: {0}", summary)
    return rundowns
  }

  fun runChainAndSummarize(
    baseUrl: String,
    graphs: List<String> = DEFAULT_CHAIN,
    seedEnv: Map<String, Any?> = emptyMap(),
  ): String =
    runChain(baseUrl, graphs, seedEnv).joinToString("\n") { it.toJson(Verbosity.SUMMARY) }
}

private val logger: System.Logger = System.getLogger("com.salesforce.revoman.harness.GraphRunner")
