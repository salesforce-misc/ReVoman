/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.salesforce.revoman.output.ledger.LedgerEntry
import com.salesforce.revoman.output.ledger.LedgerFile
import io.github.oshai.kotlinlogging.KotlinLogging
import org.yaml.snakeyaml.Yaml

internal object V3YamlReader {
  fun readCollectionDef(yaml: String): V3CollectionDef {
    val map = parseYaml(yaml)
    return mapToCollectionDef(map)
  }

  fun readRequest(yaml: String): V3Request {
    val map = parseYaml(yaml)
    return mapToRequest(map)
  }

  fun readEnv(yaml: String): V3Env {
    val map = parseYaml(yaml)
    @Suppress("UNCHECKED_CAST")
    val values = (map["values"] as? List<Map<String, Any?>>) ?: emptyList()
    return V3Env(
      name = map["name"]?.toString(),
      values =
        values.map { m ->
          V3EnvValue(
            key = m["key"]?.toString() ?: error("v3 env value missing 'key'"),
            value = m["value"]?.toString(),
          )
        },
    )
  }

  /**
   * Parse a flat top-level YAML mapping (e.g. a config file) to a plain map. Non-mapping content (a
   * top-level list or bare scalar) and empty/blank input yield an empty map.
   */
  fun readFlatMap(yaml: String): Map<String, Any?> = parseYaml(yaml)

  fun readLedger(yaml: String): LedgerFile {
    val map = parseYaml(yaml)
    @Suppress("UNCHECKED_CAST")
    val values = (map["values"] as? List<Map<String, Any?>>) ?: emptyList()
    val valueMap = values.associate { m ->
      (m["key"]?.toString() ?: error("ledger value missing 'key'")) to m["value"]?.toString()
    }
    @Suppress("UNCHECKED_CAST")
    val ledgerMeta = (map["x-revoman-ledger"] as? Map<String, Any?>) ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val stepsRaw = (ledgerMeta["steps"] as? Map<String, Map<String, Any?>>) ?: emptyMap()
    val steps = stepsRaw.mapValues { (_, e) ->
      @Suppress("UNCHECKED_CAST")
      val produces = (e["produces"] as? List<Any?>)?.map { it.toString() }?.toSet() ?: emptySet()
      @Suppress("UNCHECKED_CAST")
      val consumed = (e["consumed"] as? List<Any?>)?.map { it.toString() }?.toSet() ?: emptySet()
      LedgerEntry(produces, e["hash"]?.toString() ?: "", consumed)
    }
    return LedgerFile(
      name = map["name"]?.toString(),
      values = valueMap,
      orgId = ledgerMeta["orgId"]?.toString(),
      steps = steps,
    )
  }

  private fun mapToRequest(map: Map<String, Any?>): V3Request =
    V3Request(
      kind = strOrDefault(map["\$kind"], "http-request"),
      name = map["name"]?.toString(),
      description = map["description"]?.toString(),
      url = map["url"]?.toString() ?: error("v3 request missing required field: url"),
      method = map["method"]?.toString() ?: error("v3 request missing required field: method"),
      headers = strMap(map["headers"]),
      queryParams = strMap(map["queryParams"]),
      body = mapToBody(map["body"]),
      scripts = mapToScripts(map["scripts"]),
      auth = mapToAuthList(map["auth"]),
      settings = mapToSettings(map["settings"]),
      order = (map["order"] as? Number)?.toInt(),
    )

  private fun strMap(value: Any?): Map<String, String> {
    @Suppress("UNCHECKED_CAST") val m = value as? Map<String, Any?> ?: return emptyMap()
    return m.entries.associate { (k, v) -> k to (v?.toString() ?: "") }
  }

  private fun mapToBody(value: Any?): V3Body? {
    @Suppress("UNCHECKED_CAST") val m = value as? Map<String, Any?> ?: return null
    val type = m["type"]?.toString() ?: return null
    val content = m["content"]?.toString() ?: ""
    return V3Body(type = type, content = content)
  }

  private fun mapToScripts(value: Any?): List<V3Script> {
    @Suppress("UNCHECKED_CAST") val list = value as? List<Map<String, Any?>> ?: return emptyList()
    return list.map { m ->
      V3Script(
        type = m["type"]?.toString() ?: error("v3 script missing 'type'"),
        code = m["code"]?.toString() ?: "",
        language = m["language"]?.toString(),
      )
    }
  }

  private fun mapToSettings(value: Any?): V3Settings? {
    @Suppress("UNCHECKED_CAST") val m = value as? Map<String, Any?> ?: return null
    @Suppress("UNCHECKED_CAST")
    val disabled = (m["disabledSystemHeaders"] as? List<Any?>)?.map { it.toString() } ?: emptyList()
    return V3Settings(disabledSystemHeaders = disabled)
  }

  // * NOTE 14 Jul 2026 gopala.akshintala: Yaml is NOT thread-safe; a ThreadLocal reuses one
  // instance
  //   per thread (avoiding a fresh Yaml() per parse) while staying safe if reads are ever
  //   parallelized — do NOT "simplify" to a bare shared val. Zero cost for the sequential walk
  // today;
  //   each load() is independent, with no anchor/state carryover across documents (pinned by the
  //   sequentialReadsThroughSharedYamlAreIndependent test).
  private val yamlReader: ThreadLocal<Yaml> = ThreadLocal.withInitial { Yaml() }

  private fun parseYaml(yaml: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return (yamlReader.get().load<Any?>(yaml) as? Map<String, Any?>) ?: emptyMap()
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
