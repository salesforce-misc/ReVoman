/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Template
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PostmanCollectionGraph(
  val description: String,
  val variableNameToTemplate: Map<String, Template>,
) {
  @JsonClass(generateAdapter = true)
  data class Edge(val source: Node, val target: Node, val connectingVariable: String) {
    override fun toString(): String =
      "${source.step.name} -> ${target.step.name} :$connectingVariable:"
  }

  @JsonClass(generateAdapter = true)
  data class Node(val step: Step, val setsVariables: Set<String>, val usesVariables: Set<String>) {
    override fun toString(): String = step.name
  }

  @OptIn(ExperimentalStdlibApi::class) fun toJson(): String = initMoshi().toPrettyJson(this)
}
