/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.template

import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.postman.template.Template
import com.salesforce.revoman.internal.template.TemplateType
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.github.oshai.kotlinlogging.KotlinLogging

internal class PostmanTemplateProvider(private val moshi: Moshi = Moshi.Builder().build()) :
  TemplateProvider {
  override val format: TemplateFormat = TemplateFormat.POSTMAN_JSON

  @OptIn(ExperimentalStdlibApi::class)
  private val adapter = moshi.adapter<Template>()

  override fun supports(source: TemplateSource): Boolean {
    val extension = source.extension?.lowercase()
    if (extension == "json") return looksLikePostman(source.content)
    return looksLikePostman(source.content)
  }

  override fun parse(source: TemplateSource): TemplateParseResult {
    val template =
      runCatching { adapter.fromJson(source.content) }
        .onFailure { error -> logger.warn(error) { "Failed to parse Postman template" } }
        .getOrNull()
        ?: return TemplateParseResult(format, source.sourceName, emptyList())
    val items =
      template.item.map { item ->
        item.copy(request = item.request.copy(auth = item.request.auth ?: template.auth))
      }
    val steps = deepFlattenItems(items, templateType = TemplateType.POSTMAN)
    return TemplateParseResult(format, source.sourceName, steps)
  }

  private fun looksLikePostman(content: String): Boolean {
    val trimmed = content.trimStart()
    return trimmed.startsWith("{") && trimmed.contains("\"item\"")
  }
}

private val logger = KotlinLogging.logger {}
