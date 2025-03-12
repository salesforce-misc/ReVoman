/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.input.config.CustomDynamicVariableGenerator
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.kotest.matchers.equals.shouldNotBeEqual
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldContainAll
import io.mockk.mockk
import org.junit.jupiter.api.Test

class RegexReplacerTest {
  @Test
  fun `unmarshall Env File with Regex and Dynamic variable`() {
    val epoch = System.currentTimeMillis().toString()
    val dummyDynamicVariableGenerator = { r: String, _: PostmanSDK ->
      if (r == $$"$epoch") epoch else null
    }
    val regexReplacer = RegexReplacer(emptyMap(), dummyDynamicVariableGenerator)
    val pm = PostmanSDK(mockk(), null, regexReplacer)
    pm.environment.putAll(
      mergeEnvs(setOf("env-with-regex.json"), emptyList(), mutableMapOf("un" to "userName"))
    )
    val envWithVariablesReplaced = regexReplacer.replaceVariablesInEnv(pm)
    envWithVariablesReplaced shouldContain ("userName" to "user-$epoch@xyz.com")
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `dynamic variables - Body + dynamic env`() {
    val epoch = System.currentTimeMillis().toString()
    val dummyDynamicVariableGenerator = { key: String, _: PostmanSDK ->
      if (key == $$"$epoch") epoch else null
    }
    val regexReplacer = RegexReplacer(dynamicVariableGenerator = dummyDynamicVariableGenerator)
    val pm = PostmanSDK(mockk(), null, regexReplacer)
    pm.environment["key"] = $$"value-{{$epoch}}"
    val jsonStr =
      $$"""
      {
        "epoch": "{{$epoch}}",
        "key": "{{key}}"
      }
      """
        .trimIndent()
    val resultStr = regexReplacer.replaceVariablesRecursively(jsonStr, pm)!!
    val result = Moshi.Builder().build().adapter<Map<String, String>>().fromJson(resultStr)!!
    result shouldContainAll mapOf("epoch" to epoch, "key" to "value-$epoch")
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `custom dynamic variables`() {
    val customEpoch = "Custom - ${System.currentTimeMillis()}"
    val customDynamicVariableGenerator = CustomDynamicVariableGenerator { _, _, _ -> customEpoch }
    val noopDynamicVariableGenerator = { _: String, _: PostmanSDK -> null }
    val regexReplacer =
      RegexReplacer(
        mapOf($$"$customEpoch" to customDynamicVariableGenerator),
        noopDynamicVariableGenerator,
      )
    val pm = PostmanSDK(mockk(), null, regexReplacer)
    pm.environment["key"] = $$"value-{{$customEpoch}}"
    // This should get shadowed by custom dynamic variable
    pm.environment[$$"$customEpoch"] = $$"value-{{$customEpoch}}"
    pm.currentStepReport = mockk()
    pm.rundown = mockk()
    val jsonStr =
      $$"""
      {
        "epoch": "{{$customEpoch}}",
        "key": "{{key}}"
      }
      """
        .trimIndent()
    val resultStr = regexReplacer.replaceVariablesRecursively(jsonStr, pm)!!
    val result = Moshi.Builder().build().adapter<Map<String, String>>().fromJson(resultStr)!!
    result shouldContainAll mapOf("epoch" to customEpoch, "key" to "value-$customEpoch")
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `duplicate dynamic variables for random value generation should have different values`() {
    val regexReplacer = RegexReplacer()
    val pm = PostmanSDK(mockk(), regexReplacer = regexReplacer)
    val jsonStr =
      $$"""
      {
        "key1": "{{$randomUUID}}",
        "key2": "{{$randomUUID}}"
      }
      """
        .trimIndent()
    val resultStr = regexReplacer.replaceVariablesRecursively(jsonStr, pm)!!
    val result = Moshi.Builder().build().adapter<Map<String, String>>().fromJson(resultStr)!!
    result["key1"]!! shouldNotBeEqual result["key2"]!!
  }
}
