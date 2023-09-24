/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.internal.postman.state

import com.salesforce.revoman.internal.postman.postManVariableRegex
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
internal data class Auth(val bearer: List<Bearer>, val type: String) {
  // ! TODO 24/09/23 gopala.akshintala: When is the bearer array's `size > 1`?
  val bearerTokenKeyFromRegex: String
    get() =
      bearer.first().value.let {
        postManVariableRegex.find(it)?.groups?.get("variableKey")?.value ?: it
      }
}

@JsonClass(generateAdapter = true)
internal data class Bearer(val key: String, val type: String, val value: String)
