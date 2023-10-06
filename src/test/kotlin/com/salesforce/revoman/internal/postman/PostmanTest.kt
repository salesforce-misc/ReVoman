/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.internal.postman

import io.kotest.matchers.maps.shouldContain
import org.junit.jupiter.api.Test

class PostmanTest {

  @Test
  fun `unmarshall Env File with Regex and Dynamic variable`() {
    val epoch = System.currentTimeMillis().toString()
    val dummyDynamicVariableGenerator = { r: String -> if (r == "\$epoch") epoch else null }
    initPmEnvironment(
      listOf("env-with-regex.json"),
      mutableMapOf("un" to "userName"),
      emptyMap(),
      dummyDynamicVariableGenerator
    )
    pm.environment shouldContain ("userName" to "user-$epoch@xyz.com")
  }
}
