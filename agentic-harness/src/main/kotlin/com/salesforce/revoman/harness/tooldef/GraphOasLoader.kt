/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

import org.yaml.snakeyaml.Yaml

/** Loads a graph's OAS from `oas/<graph>.yaml` on the classpath. */
object GraphOasLoader {
  fun load(graph: String): GraphOas {
    val text =
      javaClass.classLoader.getResourceAsStream("oas/$graph.yaml")?.bufferedReader()?.readText()
        ?: error("OAS not found on classpath: oas/$graph.yaml")
    @Suppress("UNCHECKED_CAST") val root = Yaml().load<Map<String, Any?>>(text)

    @Suppress("UNCHECKED_CAST") val slotsRaw = (root["slots"] as? Map<String, Any?>).orEmpty()
    val slots =
      slotsRaw.mapValues { (_, v) ->
        @Suppress("UNCHECKED_CAST") val s = v as Map<String, Any?>
        val type = SlotType.valueOf((s["type"] as String).uppercase())
        @Suppress("UNCHECKED_CAST") val values = (s["values"] as? List<Any?>).orEmpty().map { it.toString() }
        val required = (s["required"] as? Boolean) ?: true
        SlotSchema(type, values, required)
      }

    @Suppress("UNCHECKED_CAST")
    val exampleQueries = (root["exampleQueries"] as? List<Any?>).orEmpty().map { it.toString() }
    @Suppress("UNCHECKED_CAST")
    val inputExamples =
      (root["inputExamples"] as? List<Map<String, Any?>>).orEmpty().map { ex ->
        ex.mapValues { it.value.toString() }
      }
    @Suppress("UNCHECKED_CAST")
    val whenNotToUse = (root["whenNotToUse"] as? List<Any?>).orEmpty().map { it.toString() }

    return GraphOas(root["graph"] as String, slots, exampleQueries, inputExamples, whenNotToUse)
  }
}
