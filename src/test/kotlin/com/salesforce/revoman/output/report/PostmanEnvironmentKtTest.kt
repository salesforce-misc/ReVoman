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
}
