/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.TemplateParser
import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.postman.template.Template
import com.salesforce.revoman.output.report.Step
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import okio.BufferedSource

internal class PostmanTemplateParser : TemplateParser {
  @OptIn(ExperimentalStdlibApi::class)
  private val pmTemplateAdapter = Moshi.Builder().build().adapter<Template>()

  override fun parse(sources: List<BufferedSource>): List<Step> =
    sources
      .asSequence()
      .mapNotNull { pmTemplateAdapter.fromJson(it) }
      .flatMap { (pmSteps, authFromRoot) ->
        deepFlattenItems(
          pmSteps.map { item ->
            item.copy(request = item.request.copy(auth = item.request.auth ?: authFromRoot))
          }
        )
      }
      .toList()
}
