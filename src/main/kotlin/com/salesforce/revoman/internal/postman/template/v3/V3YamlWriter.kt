/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.salesforce.revoman.output.ledger.LedgerFile
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

internal object V3YamlWriter {
  fun dump(file: LedgerFile): String {
    val root = linkedMapOf<String, Any?>()
    file.name?.let { root["name"] = it }
    // `values` in postman-env shape so the file imports into Postman unchanged.
    root["values"] =
      file.values.map { (k, v) -> linkedMapOf("key" to k, "value" to v, "enabled" to true) }
    // `x-revoman-ledger` is a SIBLING of `values` — Postman ignores it, readLedger reads it.
    root["x-revoman-ledger"] =
      linkedMapOf(
        "orgId" to file.orgId,
        "steps" to
          file.steps.mapValues { (_, e) ->
            linkedMapOf<String, Any?>("produces" to e.produces.toList(), "hash" to e.hash).apply {
              // `consumed` is provenance metadata — emit only when non-empty so files stay tidy;
              // an absent key parses back to an empty set (round-trip-equal).
              if (e.consumed.isNotEmpty()) this["consumed"] = e.consumed.toList()
            }
          },
      )
    val opts =
      DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
      }
    return Yaml(opts).dump(root)
  }
}
