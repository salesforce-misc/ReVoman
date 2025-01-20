/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.input.readFileInResourcesToString
import com.salesforce.revoman.output.postman.PostmanEnvironment
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode

class PostmanEnvironmentKtTest {
  @Test
  fun `env in Postman JSON format`() {
    val env =
      mutableMapOf(
        "key1" to 1,
        "key2" to "2",
        "key3" to listOf(1, 2, 3),
        "key4" to mapOf("4" to 4),
        "key5" to null,
      )
    val pm = PostmanEnvironment<Any?>(env)
    val postmanEnvJSONFormatStr = readFileInResourcesToString("mutable-env-to-pm.json")
    JSONAssert.assertEquals(
      pm.postmanEnvJSONFormat,
      postmanEnvJSONFormatStr,
      JSONCompareMode.STRICT,
    )
  }

  @Test
  fun `get Typed Obj`() {
    val env =
      mutableMapOf(
        "key1" to 1,
        "key2" to "2",
        "key3" to listOf(1, 2, 3),
        "key4" to mapOf("4" to 4),
        "key5" to null,
      )
    val pm = PostmanEnvironment<Any?>(env)
    pm.getObj<Int>("key1")!! shouldBeEqual env["key1"] as Int
    pm.getObj<String>("key2")!! shouldBeEqual env["key2"] as String
    pm.getObj<List<Int>>("key3")!! shouldBeEqual env["key3"] as List<Int>
    pm.getObj<Map<String, Int>>("key4")!! shouldContainExactly env["key4"] as Map<String, Int>
    pm.getObj<Any>("key5") shouldBe null
  }
}
