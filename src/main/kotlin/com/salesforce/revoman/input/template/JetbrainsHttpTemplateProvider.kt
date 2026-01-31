/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.template

import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.postman.template.Body
import com.salesforce.revoman.internal.postman.template.Event
import com.salesforce.revoman.internal.postman.template.Header
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.internal.template.TemplateType

internal class JetbrainsHttpTemplateProvider : TemplateProvider {
  override val format: TemplateFormat = TemplateFormat.JETBRAINS_HTTP

  override fun supports(source: TemplateSource): Boolean {
    val extension = source.extension?.lowercase()
    if (extension == "http" || extension == "rest") return true
    return looksLikeHttpFile(source.content)
  }

  override fun parse(source: TemplateSource): TemplateParseResult {
    val parser = JetbrainsHttpParser()
    val parseResult = parser.parse(source)
    val items = parseResult.requests.map { request -> request.toPostmanItem() }
    val steps = deepFlattenItems(items, templateType = TemplateType.JETBRAINS_HTTP)
    return TemplateParseResult(format, source.sourceName, steps, parseResult.fileVariables)
  }

  private fun looksLikeHttpFile(content: String): Boolean {
    val trimmed = content.trimStart()
    if (trimmed.startsWith("###")) return true
    val firstLine = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
    return firstLine.startsWith("http://") ||
      firstLine.startsWith("https://") ||
      firstLine.split(" ").firstOrNull()?.uppercase() in
        setOf("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "PATCH", "OPTIONS", "TRACE")
  }

  private fun JetbrainsHttpRequest.toPostmanItem(): Item {
    val events =
      listOfNotNull(
        preRequestScript?.toEvent("prerequest"),
        responseHandlerScript?.toEvent("test"),
      )
        .ifEmpty { null }
    val request =
      Request(
        method = method,
        header = headers.map { (key, value) -> Header(key, value) },
        url = Url(raw = url),
        body = body?.let { Body(mode = "raw", raw = it) },
      )
    return Item(name = name, request = request, event = events)
  }

  private fun String.toEvent(listen: String): Event =
    Event(listen = listen, script = Event.Script(exec = this.lines()))
}
