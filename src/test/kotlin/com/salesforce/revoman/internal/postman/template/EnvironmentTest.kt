/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template

import com.salesforce.revoman.input.readFileInResourcesToString
import com.salesforce.revoman.internal.json.initMoshi
import com.salesforce.revoman.internal.postman.template.Environment.Companion.fromMap
import org.junit.jupiter.api.Test
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode

class EnvironmentTest {
  private val moshiReVoman = initMoshi()

  @Test
  fun `Env in Postman Environment JSON Format`() {
    val env =
      mapOf(
        "key1" to 1,
        "key2" to "2",
        "key3" to listOf(1, 2, 3),
        "key4" to mapOf("4" to 4),
        "key5" to null,
      )
    val envFromMap = fromMap(env, moshiReVoman)
    val envInPostmanFormat = moshiReVoman.asFormatString(envFromMap)
    val envFromReVomanStr = readFileInResourcesToString("env-from-revoman.json")
    JSONAssert.assertEquals(envInPostmanFormat, envFromReVomanStr, JSONCompareMode.STRICT)
  }
}
