/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.tooldef

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Parses a V3 graph collection directory (on the classpath) into a [GraphSpec]. Placeholder and
 * output extraction is done by regex over the raw request-yaml text — more robust for finding
 * `{{var}}` tokens and `pm.environment.set` keys than parsing the YAML structure and re-scanning
 * string values.
 */
object GraphMetadataParser {
  private val INFRA_PLACEHOLDERS = setOf("baseUrl", "accessToken")
  private val PLACEHOLDER = Regex("""\{\{(\w+)}}""")
  private val ENV_SET = Regex("""pm\.environment\.set\(\s*["'](\w+)["']""")
  private val DESCRIPTION = Regex("""^description:\s*["']?(.*?)["']?\s*$""", RegexOption.MULTILINE)

  fun parse(graph: String): GraphSpec {
    val dir = resourceDir("graphs/$graph")
    val defText = dir.resolve(".resources/definition.yaml").readText()
    val description = DESCRIPTION.find(defText)?.groupValues?.get(1)?.trim().orEmpty()

    val requestText =
      Files.list(dir).use { stream ->
        stream
          .filter { it.name.endsWith(".request.yaml") }
          .map { it.readText() }
          .toList()
          .joinToString("\n")
      }
    val referenced = PLACEHOLDER.findAll(requestText).map { it.groupValues[1] }.toSet()
    val outputKeys = ENV_SET.findAll(requestText).map { it.groupValues[1] }.toSet()
    val slots = (referenced - INFRA_PLACEHOLDERS - outputKeys).sorted()
    return GraphSpec(graph, description, slots, outputKeys.sorted())
  }

  private fun resourceDir(path: String): Path {
    val url =
      javaClass.classLoader.getResource(path)
        ?: error("Graph resource directory not found on classpath: $path")
    return Path.of(url.toURI())
  }
}
