/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.input.config.CustomDynamicVariableGenerator
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Environment.Companion.mergeEnvs
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.kotest.matchers.equals.shouldNotBeEqual
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class RegexReplacerTest {
  private val moshiReVoman = initMoshi()

  @Test
  fun `unmarshall Env File with Regex and Dynamic variable`() {
    val epoch = System.currentTimeMillis().toString()
    val dummyDynamicVariableGenerator = { r: String, _: PostmanSDK ->
      if (r == $$"$epoch") epoch else null
    }
    val regexReplacer = RegexReplacer(emptyMap(), dummyDynamicVariableGenerator)
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
    pm.environment.putAll(
      mergeEnvs(setOf("env-with-regex.json"), emptyList(), mutableMapOf("un" to "userName")).values
    )
    val envWithVariablesReplaced = regexReplacer.replaceVariablesInEnv(pm)
    envWithVariablesReplaced shouldContain ("userName" to "user-$epoch@xyz.com")
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `dynamic variables replacement in JSON string and dynamic env`() {
    val epoch = System.currentTimeMillis().toString()
    val dummyDynamicVariableGenerator = { key: String, _: PostmanSDK ->
      if (key == $$"$epoch") epoch else null
    }
    val regexReplacer = RegexReplacer(dynamicVariableGenerator = dummyDynamicVariableGenerator)
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
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
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
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
    val pm = PostmanSDK(moshiReVoman)
    val jsonStr =
      $$"""
      {
        "key1": "{{$randomUUID}}",
        "key2": "{{$randomUUID}}"
      }
      """
        .trimIndent()
    val resultStr = pm.regexReplacer.replaceVariablesRecursively(jsonStr, pm)!!
    val result = Moshi.Builder().build().adapter<Map<String, String>>().fromJson(resultStr)!!
    result["key1"]!! shouldNotBeEqual result["key2"]!!
  }

  @Test
  fun `self-referencing variable does not cause StackOverflowError`() {
    val regexReplacer = RegexReplacer()
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
    pm.environment["self"] = "{{self}}"
    val result = regexReplacer.replaceVariablesRecursively("{{self}}", pm)
    result shouldBe "{{self}}"
  }

  @Test
  fun `mutually cyclic variables do not cause StackOverflowError`() {
    val regexReplacer = RegexReplacer()

    // Test resolving {{a}} where a -> b -> a
    val pm1 = PostmanSDK(moshiReVoman, null, regexReplacer)
    pm1.environment["a"] = "{{b}}"
    pm1.environment["b"] = "{{a}}"
    val resultA = regexReplacer.replaceVariablesRecursively("{{a}}", pm1)
    // When resolving {{a}}, we visit a, expand to {{b}}, visit b, expand to {{a}}, but a is already
    // visited so we break the cycle and return {{a}}
    resultA shouldBe "{{a}}"

    // Test resolving {{b}} where b -> a -> b (fresh environment)
    val pm2 = PostmanSDK(moshiReVoman, null, regexReplacer)
    pm2.environment["a"] = "{{b}}"
    pm2.environment["b"] = "{{a}}"
    val resultB = regexReplacer.replaceVariablesRecursively("{{b}}", pm2)
    resultB shouldBe "{{b}}"
  }

  @Test
  fun `two-level indirection still resolves correctly`() {
    val regexReplacer = RegexReplacer()
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
    pm.environment["a"] = "{{b}}"
    pm.environment["b"] = "value"
    val result = regexReplacer.replaceVariablesRecursively("{{a}}", pm)
    result shouldBe "value"
  }

  @Test
  fun `string without double-brace is returned unchanged`() {
    val regexReplacer = RegexReplacer()
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
    val plain = """{ "a": 1, "b": "no placeholders here", "c": "{ single brace }" }"""
    regexReplacer.replaceVariablesRecursively(plain, pm) shouldBe plain
  }

  @Test
  fun `null input stays null`() {
    val regexReplacer = RegexReplacer()
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
    regexReplacer.replaceVariablesRecursively(null, pm) shouldBe null
  }

  @Test
  fun `single unmatched brace pair is not treated as a placeholder`() {
    val regexReplacer = RegexReplacer()
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
    // Contains "{{" so it enters the regex path, but "{{ }" has no closing "}}" -> left literal.
    regexReplacer.replaceVariablesRecursively("prefix {{ } suffix", pm) shouldBe
      "prefix {{ } suffix"
  }
}
