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
    val dummyDynamicVariableGenerator = { r: String -> if (r == "\$epoch") epoch else null }
    val env =
      mergeEnvs(
        setOf("env-with-regex.json"),
        mutableMapOf("un" to "userName"),
      )
    val regexReplacer = RegexReplacer(env.toMutableMap(), emptyMap(), dummyDynamicVariableGenerator)
    val envWithVariablesReplaced = regexReplacer.replaceVariablesInEnv(mockk(), mockk())
    envWithVariablesReplaced shouldContain ("userName" to "user-$epoch@xyz.com")
  }
}
