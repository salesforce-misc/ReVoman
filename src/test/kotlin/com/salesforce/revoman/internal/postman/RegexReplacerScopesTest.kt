/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.input.config.CustomDynamicVariableGenerator
import com.salesforce.revoman.internal.log.RunLogContext
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.log.LogLevel
import com.salesforce.revoman.output.log.RunLogSink
import com.salesforce.revoman.output.log.StepEvent
import com.salesforce.revoman.output.report.Step
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Covers bound replacement precedence and the environment-only ledger/write-back side effects. */
class RegexReplacerScopesTest {
  @Test
  fun `resolves keys from each scope`() {
    val environment = focusedPostmanTestGraph(environmentValues = mapOf("k" to "env"))
    val collection = focusedPostmanTestGraph(collectionVariableValues = mapOf("k" to "collection"))
    val global = focusedPostmanTestGraph(globalValues = mapOf("k" to "global"))

    environment.replacer.replaceVariablesRecursively("v={{k}}") shouldBe "v=env"
    collection.replacer.replaceVariablesRecursively("v={{k}}") shouldBe "v=collection"
    global.replacer.replaceVariablesRecursively("v={{k}}") shouldBe "v=global"
  }

  @Test
  fun `scope precedence is environment then collection then globals`() {
    val all =
      focusedPostmanTestGraph(
        environmentValues = mapOf("k" to "environment"),
        collectionVariableValues = mapOf("k" to "collection"),
        globalValues = mapOf("k" to "global"),
      )
    val collectionAndGlobal =
      focusedPostmanTestGraph(
        collectionVariableValues = mapOf("k" to "collection"),
        globalValues = mapOf("k" to "global"),
      )

    all.replacer.replaceVariablesRecursively("{{k}}") shouldBe "environment"
    collectionAndGlobal.replacer.replaceVariablesRecursively("{{k}}") shouldBe "collection"
  }

  @Test
  fun `unknown key remains a literal placeholder`() {
    focusedPostmanTestGraph().replacer.replaceVariablesRecursively("v={{missing}}") shouldBe
      "v={{missing}}"
  }

  @Test
  fun `only environment resolution records a consumed key`() {
    val step = Step(index = "1", rawPMStep = Item(name = "s"))
    val environment = focusedPostmanTestGraph(environmentValues = mapOf("k" to "env"))
    val collection = focusedPostmanTestGraph(collectionVariableValues = mapOf("k" to "collection"))
    val global = focusedPostmanTestGraph(globalValues = mapOf("k" to "global"))
    listOf(environment, collection, global).forEach { it.scopes.environment.currentStep = step }

    environment.replacer.replaceVariablesRecursively("{{k}}") shouldBe "env"
    collection.replacer.replaceVariablesRecursively("{{k}}") shouldBe "collection"
    global.replacer.replaceVariablesRecursively("{{k}}") shouldBe "global"

    environment.scopes.environment.consumedKeysFor(step) shouldContain "k"
    collection.scopes.environment.consumedKeysFor(step) shouldNotContain "k"
    global.scopes.environment.consumedKeysFor(step) shouldNotContain "k"
  }

  @Test
  fun `collection and global values never write back into environment`() {
    val collection = focusedPostmanTestGraph(collectionVariableValues = mapOf("k" to "collection"))
    val global = focusedPostmanTestGraph(globalValues = mapOf("k" to "global"))

    collection.replacer.replaceVariablesRecursively("{{k}}") shouldBe "collection"
    global.replacer.replaceVariablesRecursively("{{k}}") shouldBe "global"

    collection.scopes.environment.containsKey("k") shouldBe false
    global.scopes.environment.containsKey("k") shouldBe false
  }

  @Test
  fun `environment numeric value is written back with its Int type preserved`() {
    val graph = focusedPostmanTestGraph(environmentValues = mapOf("n" to 7))
    graph.replacer.replaceVariablesRecursively("{{n}}") shouldBe "7"
    graph.scopes.environment["n"] shouldBe 7
  }

  @Test
  fun `custom dynamic variable takes priority over a scoped value`() {
    val custom = CustomDynamicVariableGenerator { _, _, _ -> "from-custom" }
    val graph =
      focusedPostmanTestGraph(
        environmentValues = mapOf("k" to "environment"),
        collectionVariableValues = mapOf("k" to "collection"),
        globalValues = mapOf("k" to "global"),
        customDynamicVariableGenerators = mapOf("k" to custom),
      )

    graph.replacer.replaceVariablesRecursively("{{k}}") shouldBe "from-custom"
  }

  @Test
  fun `built-in dynamic variable takes priority over a scoped value`() {
    val graph = focusedPostmanTestGraph(globalValues = mapOf($$"$currentRequestName" to "global"))
    graph.progress.currentRequestName = "focused-request"

    graph.replacer.replaceVariablesRecursively($$"{{$currentRequestName}}") shouldBe
      "focused-request"
  }

  @Test
  fun `recursive resolution chains across mixed scopes`() {
    val graph =
      focusedPostmanTestGraph(
        environmentValues = mapOf("a" to "{{b}}"),
        collectionVariableValues = mapOf("b" to "{{c}}"),
        globalValues = mapOf("c" to "leaf"),
      )

    graph.replacer.replaceVariablesRecursively("{{a}}") shouldBe "leaf"
  }

  private class RecordingSink : RunLogSink {
    val lines = mutableListOf<Pair<LogLevel, String>>()

    override fun line(level: LogLevel, message: String) {
      lines += level to message
    }

    override fun event(event: StepEvent) {}

    override fun close() {}
  }

  @AfterEach fun removeSink() = RunLogContext.remove()

  private fun debugLinesFor(seed: PostmanVariableScopes.() -> Unit): List<String> {
    val sink = RecordingSink()
    RunLogContext.install(sink)
    val graph = focusedPostmanTestGraph()
    graph.scopes.seed()
    graph.replacer.replaceVariablesRecursively("{{k}}")
    return sink.lines.filter { it.first == LogLevel.DEBUG }.map { it.second }
  }

  @Test
  fun `debug log names the environment scope`() {
    debugLinesFor { environment.set("k", "env") } shouldContain
      "{{k}} resolved from scope 'environment'"
  }

  @Test
  fun `debug log names the collection scope`() {
    debugLinesFor { collectionVariables.set("k", "collection") } shouldContain
      "{{k}} resolved from scope 'collectionVariables'"
  }

  @Test
  fun `debug log names the globals scope`() {
    debugLinesFor { globals.set("k", "global") } shouldContain "{{k}} resolved from scope 'globals'"
  }

  @Test
  fun `debug log reports only the winning scope on collision`() {
    val lines = debugLinesFor {
      collectionVariables.set("k", "collection")
      globals.set("k", "global")
    }
    lines shouldContain "{{k}} resolved from scope 'collectionVariables'"
    lines shouldNotContain "{{k}} resolved from scope 'globals'"
  }
}
