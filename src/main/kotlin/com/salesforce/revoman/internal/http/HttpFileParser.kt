/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.http

import com.salesforce.revoman.internal.postman.template.Body
import com.salesforce.revoman.internal.postman.template.Event
import com.salesforce.revoman.internal.postman.template.Event.Script
import com.salesforce.revoman.internal.postman.template.Header
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import io.github.oshai.kotlinlogging.KotlinLogging

private val HTTP_METHOD_REGEX =
  "^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|CONNECT)\\s+".toRegex()
private val HEADER_REGEX = "^([\\w-]+):\\s*(.+)$".toRegex()
private val SEPARATOR_REGEX = "^###(.*)$".toRegex()

internal object HttpFileParser {

  fun parse(httpFileContent: String): List<Item> {
    val blocks = splitIntoRequestBlocks(httpFileContent)
    return blocks.mapIndexedNotNull { index, block -> parseRequestBlock(block, index + 1) }
  }

  private fun splitIntoRequestBlocks(content: String): List<RequestBlock> {
    val blocks = mutableListOf<RequestBlock>()
    var currentName: String? = null
    var currentLines = mutableListOf<String>()

    for (line in content.lines()) {
      val separatorMatch = SEPARATOR_REGEX.matchEntire(line.trim())
      if (separatorMatch != null) {
        if (currentLines.isNotEmpty()) {
          blocks.add(RequestBlock(currentName, currentLines))
        }
        currentName = separatorMatch.groupValues[1].trim().ifBlank { null }
        currentLines = mutableListOf()
      } else {
        currentLines.add(line)
      }
    }
    if (currentLines.isNotEmpty()) {
      blocks.add(RequestBlock(currentName, currentLines))
    }
    return blocks
  }

  private fun parseRequestBlock(block: RequestBlock, index: Int): Item? {
    val lines = block.lines
    val nonBlankLines = lines.filter { it.isNotBlank() && !isComment(it) }
    if (nonBlankLines.isEmpty()) return null

    val requestLineIndex = lines.indexOfFirst { HTTP_METHOD_REGEX.containsMatchIn(it.trim()) }
    if (requestLineIndex == -1) {
      logger.warn { "No HTTP method found in block #$index, skipping" }
      return null
    }

    val requestLine = lines[requestLineIndex].trim()
    val methodMatch = HTTP_METHOD_REGEX.find(requestLine)!!
    val method = methodMatch.groupValues[1]
    val url = requestLine.substring(methodMatch.range.last + 1).trim()

    // Parse name: from block separator or @name tag, else generate
    val name =
      block.name
        ?: lines.take(requestLineIndex).firstNotNullOfOrNull { extractNameTag(it) }
        ?: "Request $index"

    // Scan lines before the request line for pre-request handlers (< {% ... %})
    val preRequestHandlerLines = mutableListOf<String>()
    var inPreReqBefore = false
    for (j in 0 until requestLineIndex) {
      val line = lines[j]
      when {
        inPreReqBefore -> {
          if (line.trim() == "%}") inPreReqBefore = false else preRequestHandlerLines.add(line)
        }
        line.trim().startsWith("< {%") -> {
          inPreReqBefore = true
          val inlinePart = line.trim().removePrefix("< {%").removeSuffix("%}").trim()
          if (line.trim().endsWith("%}") && inlinePart.isNotBlank()) {
            preRequestHandlerLines.add(inlinePart)
            inPreReqBefore = false
          }
        }
      }
    }

    // Parse headers: lines after request line until blank line or script or end
    val headers = mutableListOf<Header>()
    var bodyStartIndex = -1
    var i = requestLineIndex + 1
    while (i < lines.size) {
      val line = lines[i]
      if (line.isBlank()) {
        bodyStartIndex = i + 1
        break
      }
      if (isScriptStart(line)) break
      val headerMatch = HEADER_REGEX.matchEntire(line.trim())
      if (headerMatch != null) {
        headers.add(Header(headerMatch.groupValues[1], headerMatch.groupValues[2].trim()))
      }
      i++
    }

    // Parse body and scripts (after headers)
    val bodyLines = mutableListOf<String>()
    val responseHandlerLines = mutableListOf<String>()
    var inResponseHandler = false
    var inPostPreRequestHandler = false

    val startIndex = if (bodyStartIndex > 0) bodyStartIndex else i
    for (j in startIndex until lines.size) {
      val line = lines[j]
      when {
        inResponseHandler -> {
          if (line.trim() == "%}") inResponseHandler = false else responseHandlerLines.add(line)
        }
        inPostPreRequestHandler -> {
          if (line.trim() == "%}") inPostPreRequestHandler = false
          else preRequestHandlerLines.add(line)
        }
        line.trim().startsWith("> {%") -> {
          inResponseHandler = true
          val inlinePart = line.trim().removePrefix("> {%").removeSuffix("%}").trim()
          if (line.trim().endsWith("%}") && inlinePart.isNotBlank()) {
            responseHandlerLines.add(inlinePart)
            inResponseHandler = false
          }
        }
        line.trim().startsWith("< {%") -> {
          inPostPreRequestHandler = true
          val inlinePart = line.trim().removePrefix("< {%").removeSuffix("%}").trim()
          if (line.trim().endsWith("%}") && inlinePart.isNotBlank()) {
            preRequestHandlerLines.add(inlinePart)
            inPostPreRequestHandler = false
          }
        }
        else -> bodyLines.add(line)
      }
    }

    val bodyText = bodyLines.joinToString("\n").trim()
    val events = buildList {
      if (preRequestHandlerLines.isNotEmpty()) {
        add(Event("prerequest", Script(preRequestHandlerLines)))
      }
      if (responseHandlerLines.isNotEmpty()) {
        add(Event("test", Script(responseHandlerLines)))
      }
    }

    return Item(
      name = name,
      request =
        Request(
          method = method,
          header = headers,
          url = Url(url),
          body = if (bodyText.isNotBlank()) Body("raw", bodyText) else null,
        ),
      event = events.ifEmpty { null },
    )
  }

  private fun isComment(line: String): Boolean {
    val trimmed = line.trim()
    return (trimmed.startsWith("#") && !trimmed.startsWith("###")) || trimmed.startsWith("//")
  }

  private fun isScriptStart(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.startsWith("> {%") || trimmed.startsWith("< {%")
  }

  private fun extractNameTag(line: String): String? {
    val trimmed = line.trim()
    val tagContent =
      when {
        trimmed.startsWith("// ") -> trimmed.removePrefix("// ")
        trimmed.startsWith("# ") -> trimmed.removePrefix("# ")
        else -> return null
      }
    return if (tagContent.startsWith("@name ")) tagContent.removePrefix("@name ").trim() else null
  }

  private data class RequestBlock(val name: String?, val lines: List<String>)
}

private val logger = KotlinLogging.logger {}
