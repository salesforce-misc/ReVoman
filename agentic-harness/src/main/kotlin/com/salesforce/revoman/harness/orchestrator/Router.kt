/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.orchestrator

import com.salesforce.revoman.harness.llm.LlmClient
import com.salesforce.revoman.harness.llm.RouteDecision
import com.salesforce.revoman.harness.tooldef.ToolDef

/** Prompt A: turns an intent into a graph choice by delegating to the pluggable [LlmClient]. */
class Router(private val llm: LlmClient, private val tools: List<ToolDef>) {
  fun route(utterance: String): RouteDecision = llm.route(utterance, tools)
}
