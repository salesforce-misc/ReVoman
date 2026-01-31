/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.postman.template.Template
import com.salesforce.revoman.internal.template.TemplateLoadResult
import com.salesforce.revoman.internal.template.TemplateProvider
import com.salesforce.revoman.internal.template.TemplateSource
import com.salesforce.revoman.internal.template.TemplateType
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter

internal class PostmanTemplateProvider : TemplateProvider {
  override fun supports(source: TemplateSource): Boolean {
    val trimmed = source.content.trimStart()
    val hasPostmanKeys = trimmed.contains("\"item\"") && trimmed.contains("\"info\"")
    return when (source.extension) {
      "json" -> hasPostmanKeys
      else -> trimmed.startsWith("{") && hasPostmanKeys
    }
  }

  @OptIn(ExperimentalStdlibApi::class)
  override fun load(source: TemplateSource): TemplateLoadResult {
    val pmTemplateAdapter = Moshi.Builder().build().adapter<Template>()
    val template =
      pmTemplateAdapter.fromJson(source.content)
        ?: throw IllegalArgumentException("Invalid Postman template: ${source.name}")
    val itemsWithAuth =
      template.item.map { item ->
        item.copy(request = item.request.copy(auth = item.request.auth ?: template.auth))
      }
    val steps = deepFlattenItems(itemsWithAuth, templateType = TemplateType.POSTMAN)
    return TemplateLoadResult(TemplateType.POSTMAN, source.name, steps)
  }
}
