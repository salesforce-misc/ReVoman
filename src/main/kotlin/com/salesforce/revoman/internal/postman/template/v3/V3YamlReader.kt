/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import io.github.oshai.kotlinlogging.KotlinLogging
import org.yaml.snakeyaml.Yaml

internal object V3YamlReader {
  fun readCollectionDef(yaml: String): V3CollectionDef {
    val map = parseYaml(yaml)
    return mapToCollectionDef(map)
  }

  private fun parseYaml(yaml: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return (Yaml().load<Any?>(yaml) as? Map<String, Any?>) ?: emptyMap()
  }

  private fun mapToCollectionDef(map: Map<String, Any?>): V3CollectionDef =
    V3CollectionDef(
      kind = strOrDefault(map["\$kind"], "collection"),
      order = (map["order"] as? Number)?.toInt(),
      auth = mapToAuthList(map["auth"]),
    )

  private fun mapToAuthList(value: Any?): List<V3Auth> {
    @Suppress("UNCHECKED_CAST") val list = value as? List<Map<String, Any?>> ?: return emptyList()
    return list.map { m ->
      @Suppress("UNCHECKED_CAST")
      val credentials = (m["credentials"] as? Map<String, Any?>) ?: emptyMap()
      V3Auth(
        id = m["id"]?.toString(),
        type = m["type"]?.toString() ?: error("Auth entry missing 'type'"),
        name = m["name"]?.toString(),
        credentials = credentials.mapValues { (_, v) -> v?.toString() ?: "" },
      )
    }
  }

  private fun strOrDefault(value: Any?, default: String): String = value?.toString() ?: default
}

private val logger = KotlinLogging.logger {}
