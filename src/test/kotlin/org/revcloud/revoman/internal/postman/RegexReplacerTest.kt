/*******************************************************************************
 * Copyright (c) 2023, Salesforce, Inc.
 *  All rights reserved.
 *  SPDX-License-Identifier: BSD-3-Clause
 *  For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 ******************************************************************************/
package org.revcloud.revoman.internal.postman

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.kotest.matchers.equals.shouldNotBeEqual
import io.kotest.matchers.maps.shouldContainAll
import java.util.*
import org.junit.jupiter.api.Test

class RegexReplacerTest {

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `dynamic variables - Body + dynamic env`() {
    val epoch = System.currentTimeMillis().toString()
    val dummyDynamicVariableGenerator = { key: String -> if (key == "\$epoch") epoch else null }
    val regexReplacer =
      RegexReplacer(
        mutableMapOf("key" to "value-{{\$epoch}}"),
        dynamicVariableGenerator = dummyDynamicVariableGenerator
      )
    val jsonStr =
      """
      {
        "epoch": "{{${"$"}epoch}}",
        "key": "{{key}}"
      }
      """
        .trimIndent()
    val resultStr = regexReplacer.replaceRegexRecursively(jsonStr)!!
    val result = Moshi.Builder().build().adapter<Map<String, String>>().fromJson(resultStr)!!
    result shouldContainAll mapOf("epoch" to epoch, "key" to "value-$epoch")
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `custom dynamic variables`() {
    val customEpoch = "Custom - ${System.currentTimeMillis()}"
    val customDynamicVariableGenerator = { _: String -> customEpoch }
    val regexReplacer =
      RegexReplacer(
        mutableMapOf("key" to "value-{{\$customEpoch}}"),
        mapOf("\$customEpoch" to customDynamicVariableGenerator)
      )
    val jsonStr =
      """
      {
        "epoch": "{{${"$"}customEpoch}}",
        "key": "{{key}}"
      }
      """
        .trimIndent()
    val resultStr = regexReplacer.replaceRegexRecursively(jsonStr)!!
    val result = Moshi.Builder().build().adapter<Map<String, String>>().fromJson(resultStr)!!
    result shouldContainAll mapOf("epoch" to customEpoch, "key" to "value-$customEpoch")
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `duplicate dynamic variables should have different values`() {
    val regexReplacer = RegexReplacer()
    val jsonStr =
      """
      {
        "key1": "{{${"$"}randomUUID}}",
        "key2": "{{${"$"}randomUUID}}"
      }
      """
        .trimIndent()
    val resultStr = regexReplacer.replaceRegexRecursively(jsonStr)!!
    val result = Moshi.Builder().build().adapter<Map<String, String>>().fromJson(resultStr)!!
    result["key1"]!! shouldNotBeEqual result["key2"]!!
  }
}
