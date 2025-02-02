/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.pokemon

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AllPokemon(
  @Json(name = "count") val count: Int,
  @Json(name = "next") val next: String,
  @Json(name = "previous") val previous: Any?,
  @Json(name = "results") val results: List<Result>,
) {
  @JsonClass(generateAdapter = true)
  data class Result(@Json(name = "name") val name: String, @Json(name = "url") val url: String)
}
