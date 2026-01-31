/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.http

import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.template.TemplateLoadResult
import com.salesforce.revoman.internal.template.TemplateProvider
import com.salesforce.revoman.internal.template.TemplateSource
import com.salesforce.revoman.internal.template.TemplateType

internal class JetBrainsHttpTemplateProvider : TemplateProvider {
  override fun supports(source: TemplateSource): Boolean =
    when (source.extension) {
      "http",
      "rest",
      -> true
      else ->
        source.content.lineSequence().map { it.trim() }.any { line ->
          line.startsWith("###") ||
            line.startsWith("http://") ||
            line.startsWith("https://") ||
            line.matches(Regex("^(GET|HEAD|POST|PUT|DELETE|CONNECT|PATCH|OPTIONS|TRACE)\\s+.*"))
        }
    }

  override fun load(source: TemplateSource): TemplateLoadResult {
    val parseResult = JetBrainsHttpParser.parse(source)
    val items = parseResult.requests.map { it.toItem() }
    val steps = deepFlattenItems(items, templateType = TemplateType.JETBRAINS_HTTP)
    return TemplateLoadResult(TemplateType.JETBRAINS_HTTP, source.name, steps, parseResult.fileVariables)
  }
}
