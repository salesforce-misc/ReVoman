/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

import com.salesforce.revoman.output.Verbosity
import com.salesforce.revoman.output.toJson

/**
 * Renders a [GraphContract] to JSON at matched [Verbosity] tiers — the single serializer that emits
 * BOTH halves of the unified contract (metadata descriptor + runtime outcome) plus the data-lineage
 * and version envelope. SUMMARY is a health check; STANDARD adds the descriptions and the dataFlow
 * provenance; VERBOSE embeds the full nested ReVoman [com.salesforce.revoman.output.Rundown] JSON.
 */
fun GraphContract.toJson(verbosity: Verbosity = Verbosity.STANDARD): String {
  val sb = StringBuilder()
  sb.append("{")
  sb.append("\"contractVersion\":").append(str(contractVersion))
  sb.append(",\"graph\":").append(str(descriptor.graphName))
  sb.append(",\"succeeded\":").append(succeeded)
  sb.append(",\"invocationSlots\":").append(strMap(invocationSlots))
  // SUMMARY-and-up: runtime stats.
  sb.append(",\"stopReason\":").append(str(outcome.stopReason.toString()))
  sb.append(",\"executedStepCount\":").append(outcome.executedStepCount)
  sb.append(",\"unsuccessfulStepCount\":").append(outcome.unsuccessfulStepCount)

  if (verbosity == Verbosity.STANDARD || verbosity == Verbosity.VERBOSE) {
    sb.append(",\"whenToUse\":").append(str(descriptor.whenToUse))
    sb.append(",\"whenNotToUse\":").append(strList(descriptor.whenNotToUse))
    sb.append(",\"dataFlow\":").append(dataFlowJson())
  }
  if (verbosity == Verbosity.VERBOSE) {
    sb.append(",\"exampleQueries\":").append(strList(descriptor.exampleQueries))
    sb.append(",\"inputExamples\":").append(strMapList(descriptor.inputExamples))
    // Embed the full ReVoman runtime JSON as a nested object (already valid JSON).
    sb.append(",\"rundown\":").append(outcome.toJson(Verbosity.VERBOSE))
  }
  sb.append("}")
  return sb.toString()
}

private fun GraphContract.dataFlowJson(): String =
  dataFlow.joinToString(",", "[", "]") { edge ->
    "{\"key\":${str(edge.key)}," +
      "\"producedByStep\":${edge.producedByStep?.let { str(it) } ?: "null"}," +
      "\"consumedBySteps\":${strList(edge.consumedBySteps)}}"
  }

private fun str(s: String): String {
  val sb = StringBuilder("\"")
  for (c in s) {
    when (c) {
      '\\' -> sb.append("\\\\")
      '"' -> sb.append("\\\"")
      '\n' -> sb.append("\\n")
      '\r' -> sb.append("\\r")
      '\t' -> sb.append("\\t")
      '\b' -> sb.append("\\b")
      '' -> sb.append("\\f")
      else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
    }
  }
  sb.append("\"")
  return sb.toString()
}

private fun strList(xs: List<String>): String = xs.joinToString(",", "[", "]") { str(it) }

private fun strMap(m: Map<String, String>): String =
  m.entries.joinToString(",", "{", "}") { "${str(it.key)}:${str(it.value)}" }

private fun strMapList(ms: List<Map<String, String>>): String =
  ms.joinToString(",", "[", "]") { strMap(it) }
