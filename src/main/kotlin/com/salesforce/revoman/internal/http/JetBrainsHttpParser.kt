/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.http

import com.salesforce.revoman.input.readFileToString
import com.salesforce.revoman.internal.postman.template.Body
import com.salesforce.revoman.internal.postman.template.Event
import com.salesforce.revoman.internal.postman.template.Header
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.internal.template.TemplateSource

internal data class JetBrainsHttpRequest(
  val name: String,
  val method: String,
  val url: String,
  val headers: List<Header> = emptyList(),
  val body: String? = null,
  val preRequestScript: String? = null,
  val responseHandlerScript: String? = null,
) {
  fun toItem(): Item {
    val events =
      buildList {
        preRequestScript
          ?.takeIf { it.isNotBlank() }
          ?.let { add(Event("prerequest", Event.Script(it.lines()))) }
        responseHandlerScript
          ?.takeIf { it.isNotBlank() }
          ?.let { add(Event("test", Event.Script(it.lines()))) }
      }
    val requestBody = body?.let { Body(mode = "raw", raw = it) }
    return Item(
      name = name,
      request = Request(method = method, header = headers, url = Url(url), body = requestBody),
      event = events.ifEmpty { null },
    )
  }
}

internal data class JetBrainsHttpParseResult(
  val requests: List<JetBrainsHttpRequest>,
  val fileVariables: Map<String, Any?>,
)

internal object JetBrainsHttpParser {
  private val supportedMethods =
    setOf("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "PATCH", "OPTIONS", "TRACE")

  fun parse(source: TemplateSource): JetBrainsHttpParseResult {
    val lines = normalizeLines(source.content)
    val requests = mutableListOf<JetBrainsHttpRequest>()
    val fileVariables = linkedMapOf<String, Any?>()
    var pendingName: String? = null
    val pendingPreScripts = mutableListOf<String>()
    var index = 0
    while (index < lines.size) {
      val line = lines[index].trimEnd()
      val trimmed = line.trim()
      if (trimmed.isBlank()) {
        index++
        continue
      }
      if (isRequestSeparator(trimmed)) {
        val nameCandidate = trimmed.removePrefix("###").trim()
        if (nameCandidate.isNotBlank()) {
          pendingName = nameCandidate
        }
        index++
        continue
      }
      if (isNameComment(trimmed)) {
        pendingName = parseNameComment(trimmed)
        index++
        continue
      }
      if (isFileVariableLine(trimmed)) {
        parseFileVariable(trimmed)?.let { (key, value) -> fileVariables[key] = value }
        index++
        continue
      }
      if (isComment(trimmed)) {
        index++
        continue
      }
      if (isPreRequestScriptLine(trimmed)) {
        val (script, nextIndex) = parseScriptBlock(lines, index, source, '<')
        pendingPreScripts.add(script)
        index = nextIndex
        continue
      }

      val (method, url, nextIndex) = parseRequestLine(lines, index)
      index = nextIndex
      val headers = mutableListOf<Header>()
      while (index < lines.size) {
        val headerLine = lines[index].trimEnd()
        val headerTrimmed = headerLine.trim()
        if (headerTrimmed.isBlank()) {
          index++
          break
        }
        if (isRequestSeparator(headerTrimmed) ||
          headerTrimmed.startsWith(">") ||
          headerTrimmed.startsWith("<>")) {
          break
        }
        if (isComment(headerTrimmed)) {
          index++
          continue
        }
        if (headerLine.startsWith(" ") || headerLine.startsWith("\t")) {
          val lastHeader = headers.lastOrNull()
          if (lastHeader != null) {
            headers[headers.lastIndex] =
              lastHeader.copy(value = "${lastHeader.value} ${headerTrimmed.trim()}")
          }
          index++
          continue
        }
        val parts = headerLine.split(":", limit = 2)
        if (parts.size == 2) {
          headers.add(Header(parts[0].trim(), parts[1].trim()))
        }
        index++
      }

      val bodyLines = mutableListOf<String>()
      while (index < lines.size) {
        val bodyLine = lines[index].trimEnd()
        val bodyTrimmed = bodyLine.trim()
        if (isRequestSeparator(bodyTrimmed) ||
          bodyTrimmed.startsWith(">") ||
          bodyTrimmed.startsWith("<>")) {
          break
        }
        if (bodyTrimmed.startsWith("<") && bodyLines.isEmpty()) {
          val fileRef = bodyTrimmed.removePrefix("<").trim()
          val resolvedPath = source.resolveRelativePath(fileRef)
          bodyLines.add(readFileToString(resolvedPath))
          index++
          break
        }
        bodyLines.add(bodyLine)
        index++
      }
      val body = bodyLines.joinToString("\n").ifBlank { null }

      var responseHandlerScript: String? = null
      if (index < lines.size) {
        val responseLine = lines[index].trim()
        if (responseLine.startsWith(">") && !responseLine.startsWith(">>")) {
          val (script, nextIndex) = parseScriptBlock(lines, index, source, '>')
          responseHandlerScript = script
          index = nextIndex
        } else if (responseLine.startsWith("<>")) {
          index++
        }
      }

      val resolvedUrl = ensureUrlHasHost(url, headers)
      val name =
        pendingName?.takeIf { it.isNotBlank() }
          ?: "$method ${resolvedUrl.trim().ifBlank { "request-${requests.size + 1}" }}"
      val preRequestScript =
        pendingPreScripts.joinToString("\n").ifBlank { null }
      pendingPreScripts.clear()
      pendingName = null

      requests.add(
        JetBrainsHttpRequest(
          name = name,
          method = method,
          url = resolvedUrl,
          headers = headers,
          body = body,
          preRequestScript = preRequestScript,
          responseHandlerScript = responseHandlerScript,
        )
      )
    }
    return JetBrainsHttpParseResult(requests, fileVariables)
  }

  private fun parseRequestLine(
    lines: List<String>,
    startIndex: Int,
  ): Triple<String, String, Int> {
    val line = lines[startIndex].trimEnd()
    val trimmed = line.trim()
    val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
    val firstToken = tokens.firstOrNull().orEmpty()
    val hasMethod = supportedMethods.contains(firstToken.uppercase())
    val method = if (hasMethod) firstToken.uppercase() else "GET"
    val rest = if (hasMethod) trimmed.removePrefix(firstToken).trim() else trimmed
    val urlWithMaybeVersion = stripHttpVersion(rest)
    val urlBuilder = StringBuilder(urlWithMaybeVersion)
    var index = startIndex + 1
    while (index < lines.size) {
      val nextLine = lines[index]
      if (!nextLine.startsWith(" ") && !nextLine.startsWith("\t")) {
        break
      }
      val continuation = nextLine.trim()
      if (continuation.isNotBlank()) {
        urlBuilder.append(continuation)
      }
      index++
    }
    return Triple(method, urlBuilder.toString(), index)
  }

  private fun stripHttpVersion(input: String): String {
    val parts = input.split(Regex("\\s+"))
    if (parts.isEmpty()) {
      return input
    }
    val last = parts.last()
    val isHttpVersion = last.startsWith("HTTP/") || last.startsWith("HTTP/2")
    return if (isHttpVersion) {
      parts.dropLast(1).joinToString(" ")
    } else {
      input
    }
  }

  private fun parseScriptBlock(
    lines: List<String>,
    startIndex: Int,
    source: TemplateSource,
    prefix: Char,
  ): Pair<String, Int> {
    val line = lines[startIndex].trim()
    val prefixStripped = line.dropWhile { it == prefix }.trimStart()
    if (prefixStripped.startsWith("{%")) {
      val inlineStart = prefixStripped.removePrefix("{%")
      if (inlineStart.contains("%}")) {
        val script = inlineStart.substringBefore("%}")
        return script.trim() to (startIndex + 1)
      }
      val scriptLines = mutableListOf<String>()
      if (inlineStart.isNotBlank()) {
        scriptLines.add(inlineStart)
      }
      var index = startIndex + 1
      while (index < lines.size) {
        val current = lines[index]
        val endIndex = current.indexOf("%}")
        if (endIndex != -1) {
          scriptLines.add(current.substring(0, endIndex))
          index++
          break
        }
        scriptLines.add(current)
        index++
      }
      return scriptLines.joinToString("\n") to index
    }
    val fileRef = prefixStripped.trim()
    val resolvedPath = source.resolveRelativePath(fileRef)
    return readFileToString(resolvedPath) to (startIndex + 1)
  }

  private fun ensureUrlHasHost(url: String, headers: List<Header>): String {
    val trimmed = url.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      return trimmed
    }
    val hostHeader = headers.firstOrNull { it.key.equals("Host", ignoreCase = true) }?.value
    return if (!hostHeader.isNullOrBlank()) {
      val normalizedHost = hostHeader.trim().removePrefix("http://").removePrefix("https://")
      "http://$normalizedHost${if (trimmed.startsWith("/")) trimmed else "/$trimmed"}"
    } else {
      trimmed
    }
  }

  private fun normalizeLines(content: String): List<String> =
    content.replace("\r\n", "\n").replace("\r", "\n").split("\n")

  private fun isRequestSeparator(trimmed: String): Boolean = trimmed.startsWith("###")

  private fun isComment(trimmed: String): Boolean =
    trimmed.startsWith("#") || trimmed.startsWith("//")

  private fun isNameComment(trimmed: String): Boolean =
    trimmed.startsWith("# @name") || trimmed.startsWith("// @name")

  private fun parseNameComment(trimmed: String): String {
    val withoutPrefix =
      trimmed
        .removePrefix("#")
        .removePrefix("//")
        .trim()
        .removePrefix("@name")
        .trim()
    return withoutPrefix.removePrefix("=").trim()
  }

  private fun isFileVariableLine(trimmed: String): Boolean =
    trimmed.startsWith("@") && trimmed.contains("=")

  private fun parseFileVariable(trimmed: String): Pair<String, String>? {
    val withoutPrefix = trimmed.removePrefix("@").trim()
    val parts = withoutPrefix.split("=", limit = 2)
    if (parts.size < 2) return null
    val key = parts[0].trim()
    val value = parts[1].trim()
    return if (key.isBlank()) null else key to value
  }

  private fun isPreRequestScriptLine(trimmed: String): Boolean =
    trimmed.startsWith("<") && !trimmed.startsWith("<>")
}
