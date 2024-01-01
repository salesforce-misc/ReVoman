/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ****************************************************************************
 */
package com.salesforce.revoman.internal.postman.state

import com.salesforce.revoman.internal.postman.postManVariableRegex
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Auth(val bearer: List<Bearer>, val type: String) {
  @JsonClass(generateAdapter = true)
  data class Bearer(val key: String, val type: String, val value: String)

  // ! TODO 24/09/23 gopala.akshintala: When is the bearer array's `size > 1`?
  val bearerTokenKeyFromRegex: String
    get() =
      bearer.first().value.let {
        postManVariableRegex.find(it)?.groups?.get("variableKey")?.value ?: it
      }
}
