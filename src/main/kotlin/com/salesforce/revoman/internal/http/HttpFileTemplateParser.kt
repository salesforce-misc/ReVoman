/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.http

import com.salesforce.revoman.internal.TemplateParser
import com.salesforce.revoman.output.report.Step
import okio.BufferedSource

internal class HttpFileTemplateParser : TemplateParser {
  override fun parse(sources: List<BufferedSource>): List<Step> =
    sources
      .asSequence()
      .flatMap { source ->
        val content = source.readUtf8()
        HttpFileParser.parse(content)
      }
      .mapIndexed { index, item -> Step("${index + 1}", item) }
      .toList()
}
