/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import io.kotest.matchers.maps.shouldContain
import io.mockk.mockk
import org.junit.jupiter.api.Test

class PostmanTest {

  @Test
  fun `unmarshall Env File with Regex and Dynamic variable`() {
    val epoch = System.currentTimeMillis().toString()
    val dummyDynamicVariableGenerator = { r: String, _: PostmanSDK ->
      if (r == "\$epoch") epoch else null
    }
    val regexReplacer = RegexReplacer(emptyMap(), dummyDynamicVariableGenerator)
    val pm = PostmanSDK(mockk(), null, regexReplacer)
    pm.environment.putAll(
      mergeEnvs(
        setOf("env-with-regex.json"),
        emptyList(),
        mutableMapOf("un" to "userName"),
      )
    )
    val envWithVariablesReplaced = regexReplacer.replaceVariablesInEnv(pm)
    envWithVariablesReplaced shouldContain ("userName" to "user-$epoch@xyz.com")
  }
}
