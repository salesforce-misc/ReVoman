/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.input.config.CustomDynamicVariableGenerator
import com.salesforce.revoman.internal.postman.template.Environment.Companion.mergeEnvs
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.kotest.matchers.equals.shouldNotBeEqual
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RegexReplacerTest {
  @Test
  fun `unmarshall Env File with Regex and custom dynamic variable`() {
    val epoch = System.currentTimeMillis().toString()
    val graph =
      focusedPostmanTestGraph(
        customDynamicVariableGenerators =
          mapOf($$"$epoch" to CustomDynamicVariableGenerator { _, _, _ -> epoch })
      )
    graph.scopes.environment.putAll(
      mergeEnvs(setOf("env-with-regex.json"), emptyList(), mutableMapOf("un" to "userName")).values
    )

    val replaced = graph.replacer.replaceVariablesInEnv()

    replaced shouldContain ("userName" to "user-$epoch@xyz.com")
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `built-in dynamic variables replace recursively in JSON and environment values`() {
    val graph = focusedPostmanTestGraph()
    graph.progress.currentRequestName = "request-42"
    graph.scopes.environment["key"] = $$"value-{{$currentRequestName}}"
    val json =
      $$"""
      {
        "request": "{{$currentRequestName}}",
        "key": "{{key}}"
      }
      """
        .trimIndent()

    val result =
      Moshi.Builder()
        .build()
        .adapter<Map<String, String>>()
        .fromJson(graph.replacer.replaceVariablesRecursively(json)!!)!!

    result shouldContainAll mapOf("request" to "request-42", "key" to "value-request-42")
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `custom dynamic variables shadow a same-named environment value`() {
    val customValue = "Custom - ${System.currentTimeMillis()}"
    val key = $$"$customEpoch"
    val graph =
      focusedPostmanTestGraph(
        environmentValues = mapOf("key" to "value-{{$key}}", key to "environment"),
        customDynamicVariableGenerators =
          mapOf(key to CustomDynamicVariableGenerator { _, _, _ -> customValue }),
      )
    val json = """{"epoch":"{{$key}}","key":"{{key}}"}"""

    val result =
      Moshi.Builder()
        .build()
        .adapter<Map<String, String>>()
        .fromJson(graph.replacer.replaceVariablesRecursively(json)!!)!!

    result shouldContainAll mapOf("epoch" to customValue, "key" to "value-$customValue")
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `duplicate random dynamic variables generate different values`() {
    val replacer = focusedPostmanTestGraph().replacer
    val json =
      $$"""
      {
        "key1": "{{$randomUUID}}",
        "key2": "{{$randomUUID}}"
      }
      """
        .trimIndent()
    val result =
      Moshi.Builder()
        .build()
        .adapter<Map<String, String>>()
        .fromJson(replacer.replaceVariablesRecursively(json)!!)!!
    result["key1"]!! shouldNotBeEqual result["key2"]!!
  }

  @Test
  fun `self reference remains unresolved instead of overflowing`() {
    val graph = focusedPostmanTestGraph(environmentValues = mapOf("self" to "{{self}}"))
    graph.replacer.replaceVariablesRecursively("{{self}}") shouldBe "{{self}}"
  }

  @Test
  fun `mutual cycles remain unresolved from either entry point`() {
    val values = mapOf("a" to "{{b}}", "b" to "{{a}}")

    focusedPostmanTestGraph(environmentValues = values)
      .replacer
      .replaceVariablesRecursively("{{a}}") shouldBe "{{a}}"
    focusedPostmanTestGraph(environmentValues = values)
      .replacer
      .replaceVariablesRecursively("{{b}}") shouldBe "{{b}}"
  }

  @Test
  fun `two-level indirection resolves`() {
    val graph = focusedPostmanTestGraph(environmentValues = mapOf("a" to "{{b}}", "b" to "value"))
    graph.replacer.replaceVariablesRecursively("{{a}}") shouldBe "value"
  }

  @Test
  fun `plain null and unmatched inputs are unchanged`() {
    val replacer = focusedPostmanTestGraph().replacer
    val plain = """{ "a": 1, "b": "no placeholders", "c": "{ single brace }" }"""
    replacer.replaceVariablesRecursively(plain) shouldBe plain
    replacer.replaceVariablesRecursively(null) shouldBe null
    replacer.replaceVariablesRecursively("prefix {{ } suffix") shouldBe "prefix {{ } suffix"
  }

  @Test
  fun `replaceVariablesInEnv resolves placeholders and preserves static typed values`() {
    val graph =
      focusedPostmanTestGraph(
        environmentValues =
          mapOf(
            "base" to "example.com",
            "url" to "https://{{base}}/api",
            "staticStr" to "no placeholders",
            "count" to 42,
            "flag" to true,
          )
      )

    graph.replacer.replaceVariablesInEnv() shouldContainAll
      mapOf(
        "base" to "example.com",
        "url" to "https://example.com/api",
        "staticStr" to "no placeholders",
        "count" to 42,
        "flag" to true,
      )
  }

  @Test
  fun `replaceVariablesInEnv resolves a placeholder in the key`() {
    val graph =
      focusedPostmanTestGraph(
        environmentValues = mapOf("name" to "userName", "{{name}}" to "value")
      )
    graph.replacer.replaceVariablesInEnv() shouldContain ("userName" to "value")
  }
}
