/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.telemetry

/**
 * One completed span in the OpenTelemetry GenAI-convention shape: a name, `gen_ai.*` attributes,
 * and nested child spans. This is a faithful model of the convention's span tree rendered to a
 * console sink — not the OTLP wire SDK (no dependency), which is what the local demo needs.
 */
data class Span(val name: String, val attributes: Map<String, Any?>, val children: List<Span>) {
  fun render(indent: Int = 0): String {
    val pad = "  ".repeat(indent)
    val attrs = attributes.entries.joinToString(", ") { "${it.key}=${it.value}" }
    val head = "$pad• $name" + if (attrs.isEmpty()) "" else "  [$attrs]"
    val kids = children.joinToString("") { "\n" + it.render(indent + 1) }
    return head + kids
  }
}
