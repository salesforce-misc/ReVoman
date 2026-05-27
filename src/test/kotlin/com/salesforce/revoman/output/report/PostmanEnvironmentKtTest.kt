/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.input.readFileToString
import com.salesforce.revoman.output.postman.PostmanEnvironment
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.maps.shouldNotBeEmpty
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
    val pmEnv = PostmanEnvironment(env)
    val postmanEnvJSONFormatStr = readFileToString("env-from-revoman.json")
    JSONAssert.assertEquals(
      pmEnv.postmanEnvJSONFormat,
      postmanEnvJSONFormatStr,
      JSONCompareMode.STRICT,
    )
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun getObj() {
    val env =
      mutableMapOf(
        "key1" to 1,
        "key2" to "2",
        "key3" to listOf(1, 2, 3),
        "key4" to mapOf("4" to 4),
        "key5" to listOf("a", "b", "c"),
        "key6" to
          """
          {
            "attributes": {
              "type": "BillingSchedule",
              "url": "/services/data/v64.0/sobjects/BillingSchedule/44bSG000000WOyTYAW"
            },
            "Id": "44bSG000000WOyTYAW",
            "Status": "ReadyForInvoicing",
            "NextBillingDate": "2024-10-07",
            "NextChargeFromDate": "2024-10-07",
            "BilledThroughPeriod": null,
            "CancellationDate": null,
            "TotalAmount": 89.99,
            "Category": "Original"
          }
          """,
        "key7" to "some string",
        "key8" to null,
      )
    val pmEnv = PostmanEnvironment(env)
    pmEnv.getObj<Int>("key1")!! shouldBeEqual env["key1"] as Int
    pmEnv.getObj<String>("key2")!! shouldBeEqual env["key2"] as String
    pmEnv.getObj<List<Int>>("key3")!! shouldBeEqual env["key3"] as List<Int>
    pmEnv.getObj<Map<String, Int>>("key4")!! shouldContainExactly env["key4"] as Map<String, Int>
    pmEnv.getObj<List<String>>("key5")!! shouldContainExactly env["key5"] as List<String>
    pmEnv.getObj<Map<String, Any?>>("key6")!!.shouldNotBeEmpty()
    pmEnv.getObj<String>("key7") shouldBe env["key7"]
    pmEnv.getObj<Any>("key8") shouldBe null
  }

  @Test
  fun getAsString() {
    val env =
      mutableMapOf(
        "key1" to "hello",
        "key2" to "",
        "key3" to 42,
        "key4" to true,
        "key5" to 3.14,
        "key6" to listOf(1, 2, 3),
        "key7" to mapOf("a" to 1),
        "key8" to null,
      )
    val pmEnv = PostmanEnvironment(env)
    pmEnv.getAsString("key1") shouldBe "hello"
    pmEnv.getAsString("key2") shouldBe ""
    pmEnv.getAsString("key3") shouldBe "42"
    pmEnv.getAsString("key4") shouldBe "true"
    pmEnv.getAsString("key5") shouldBe "3.14"
    pmEnv.getAsString("key6") shouldBe "[1,2,3]"
    JSONAssert.assertEquals("""{"a":1}""", pmEnv.getAsString("key7"), JSONCompareMode.STRICT)
    pmEnv.getAsString("key8") shouldBe "null"
    pmEnv.getAsString("missing") shouldBe "null"
    pmEnv.getAsString(null) shouldBe "null"
  }
}
