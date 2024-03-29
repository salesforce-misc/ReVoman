/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Auth(val bearer: List<Bearer>, val type: String) {
  // ! TODO 09/01/24 gopala.akshintala: Support other ways to authorize
  @JsonClass(generateAdapter = true)
  data class Bearer(val key: String, val type: String, val value: String)
}
