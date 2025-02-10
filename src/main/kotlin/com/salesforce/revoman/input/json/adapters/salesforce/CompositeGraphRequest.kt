/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json.adapters.salesforce

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CompositeGraphRequest(val graphs: List<Graph>) {
  @JsonClass(generateAdapter = true)
  data class Graph(val compositeRequest: List<CompositeRequest>, val graphId: String) {
    @JsonClass(generateAdapter = true)
    data class CompositeRequest(
      val body: Any,
      val method: String,
      val referenceId: String,
      val url: String,
    )
  }
}
