/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.llm.claude

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.llm.RouteDecision
import com.salesforce.revoman.harness.tooldef.ToolDef
import kotlinx.coroutines.runBlocking

/**
 * The REAL [LlmClient] backed by Claude via koog. Confined to the isolated `claude` source set so
 * koog never touches the CI-tested core. Bridges koog's suspend API with [runBlocking] to satisfy
 * the synchronous [LlmClient] contract. Never required to run tests — gated on [fromEnv].
 */
class ClaudeLlmClient(apiKey: String) : LlmClient {
  private val executor = simpleAnthropicExecutor(apiKey)
  private val model = AnthropicModels.Sonnet_4_5

  override fun route(utterance: String, tools: List<ToolDef>): RouteDecision {
    val toolBlock =
      tools.joinToString("\n\n") { t ->
        buildString {
          appendLine("graph: ${t.graphName}")
          appendLine("when_to_use: ${t.whenToUse}")
          appendLine("when_not_to_use: ${t.whenNotToUse.joinToString("; ")}")
          appendLine("example_queries: ${t.exampleQueries.joinToString("; ")}")
        }
      }
    val text =
      runBlocking {
        executor
          .execute(
            prompt("route") {
              system(
                "You are a router. Choose exactly ONE graph name for the user's request, or the " +
                  "literal word NONE if no graph fits. Reply with ONLY the graph name or NONE.\n\n" +
                  toolBlock
              )
              user(utterance)
            },
            model,
          )
          .textContent()
          .trim()
      }
    val chosen = tools.firstOrNull { it.graphName.equals(text, ignoreCase = true) }?.graphName
    return RouteDecision(chosen, "claude replied: $text")
  }

  override fun fillSlots(utterance: String, tool: ToolDef): Map<String, String> {
    val schema =
      tool.slots.entries.joinToString("\n") { (name, s) ->
        "- $name: type=${s.type}${if (s.values.isNotEmpty()) ", allowed=${s.values}" else ""}"
      }
    val examples = tool.inputExamples.joinToString("\n") { it.toString() }
    val text =
      runBlocking {
        executor
          .execute(
            prompt("slot-fill") {
              system(
                "Extract the input slots for the '${tool.graphName}' graph from the user request. " +
                  "Reply with ONLY compact JSON of slot->string-value. Use only these slots:\n" +
                  "$schema\n\nExamples:\n$examples"
              )
              user(utterance)
            },
            model,
          )
          .textContent()
          .trim()
      }
    return parseFlatJson(text)
  }

  /** Minimal flat-JSON parser for `{"k":"v",...}` — the slot-fill reply shape. */
  private fun parseFlatJson(json: String): Map<String, String> =
    Regex(""""(\w+)"\s*:\s*"?([^",}]+)"?""")
      .findAll(json)
      .associate { it.groupValues[1] to it.groupValues[2].trim() }

  companion object {
    fun fromEnv(): LlmClient? = System.getenv("ANTHROPIC_API_KEY")?.let(::ClaudeLlmClient)
  }
}
