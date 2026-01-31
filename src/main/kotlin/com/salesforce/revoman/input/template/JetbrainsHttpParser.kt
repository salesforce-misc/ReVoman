/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.template

import com.salesforce.revoman.input.readFileToString
import java.io.File

internal data class JetbrainsHttpRequest(
  val name: String,
  val method: String,
  val url: String,
  val headers: List<Pair<String, String>>,
  val body: String?,
  val preRequestScript: String?,
  val responseHandlerScript: String?,
)

internal data class JetbrainsHttpParseResult(
  val requests: List<JetbrainsHttpRequest>,
  val fileVariables: Map<String, Any?>,
)

internal class JetbrainsHttpParser {
  fun parse(source: TemplateSource): JetbrainsHttpParseResult {
    val lines = normalizeLines(source.content)
    val requests = mutableListOf<JetbrainsHttpRequest>()
    val fileVariables = linkedMapOf<String, Any?>()
    var index = 0
    var pendingName: String? = null
    var pendingPreRequestScript: String? = null

    while (index < lines.size) {
      val rawLine = lines[index]
      val trimmed = rawLine.trim()

      if (trimmed.isBlank()) {
        index++
        continue
      }

      if (isSeparator(trimmed)) {
        pendingName = parseSeparatorName(trimmed) ?: pendingName
        pendingPreRequestScript = null
        index++
        continue
      }

      if (isNameLine(trimmed)) {
        pendingName = parseNameLine(trimmed) ?: pendingName
        index++
        continue
      }

      if (isInPlaceVariableLine(trimmed)) {
        parseInPlaceVariable(trimmed)?.let { (key, value) -> fileVariables[key] = value }
        index++
        continue
      }

      if (isPreRequestScriptLine(trimmed)) {
        val (script, nextIndex) = readScript(lines, index, source)
        pendingPreRequestScript = script
        index = nextIndex
        continue
      }

      if (isCommentLine(trimmed)) {
        index++
        continue
      }

      val requestLine = rawLine
      val (method, url) = parseRequestLine(requestLine)
      index++

      val headers = mutableListOf<Pair<String, String>>()
      while (index < lines.size && lines[index].isNotBlank()) {
        val headerLine = lines[index]
        val headerTrimmed = headerLine.trim()
        if (!isCommentLine(headerTrimmed)) {
          val parsed = parseHeader(headerLine)
          if (parsed != null) {
            headers.add(parsed)
          }
        }
        index++
      }

      if (index < lines.size && lines[index].isBlank()) {
        index++
      }

      val bodyLines = mutableListOf<String>()
      while (index < lines.size) {
        val bodyLine = lines[index]
        val bodyTrimmed = bodyLine.trim()
        if (isSeparator(bodyTrimmed) || isResponseHandlerLine(bodyTrimmed) || isResponseRefLine(bodyTrimmed)) {
          break
        }
        if (!isCommentLine(bodyTrimmed)) {
          bodyLines.add(bodyLine)
        }
        index++
      }

      val body = resolveBody(bodyLines, source)

      var responseHandlerScript: String? = null
      if (index < lines.size && isResponseHandlerLine(lines[index].trim())) {
        val (script, nextIndex) = readScript(lines, index, source)
        responseHandlerScript = script
        index = nextIndex
      }

      while (index < lines.size && isResponseRefLine(lines[index].trim())) {
        index++
      }

      val requestName = pendingName ?: deriveName(method, url)
      requests.add(
        JetbrainsHttpRequest(
          name = requestName,
          method = method,
          url = url,
          headers = headers,
          body = body,
          preRequestScript = pendingPreRequestScript,
          responseHandlerScript = responseHandlerScript,
        )
      )
      pendingName = null
      pendingPreRequestScript = null
    }

    return JetbrainsHttpParseResult(requests, fileVariables)
  }

  private fun normalizeLines(content: String): List<String> =
    content.replace("\r\n", "\n").replace("\r", "\n").split("\n")

  private fun isSeparator(trimmed: String) = trimmed.startsWith("###")

  private fun parseSeparatorName(trimmed: String): String? =
    trimmed.removePrefix("###").trim().ifBlank { null }

  private fun isNameLine(trimmed: String): Boolean =
    trimmed.startsWith("# @name") || trimmed.startsWith("// @name")

  private fun parseNameLine(trimmed: String): String? {
    val sanitized =
      trimmed
        .removePrefix("#")
        .removePrefix("//")
        .trim()
    if (!sanitized.startsWith("@name")) return null
    val withoutPrefix = sanitized.removePrefix("@name").trim()
    return withoutPrefix.removePrefix("=").trim().ifBlank { null }
  }

  private fun isCommentLine(trimmed: String) = trimmed.startsWith("#") || trimmed.startsWith("//")

  private fun isInPlaceVariableLine(trimmed: String): Boolean =
    trimmed.startsWith("@") && trimmed.contains("=")

  private fun parseInPlaceVariable(trimmed: String): Pair<String, String>? {
    val withoutPrefix = trimmed.removePrefix("@").trim()
    val parts = withoutPrefix.split("=", limit = 2)
    if (parts.size < 2) return null
    val key = parts[0].trim()
    val value = parts[1].trim()
    return if (key.isBlank()) null else key to value
  }

  private fun isPreRequestScriptLine(trimmed: String): Boolean =
    trimmed.startsWith("<") && !trimmed.startsWith("<>")

  private fun isResponseHandlerLine(trimmed: String): Boolean =
    trimmed.startsWith(">") && !trimmed.startsWith(">>")

  private fun isResponseRefLine(trimmed: String): Boolean = trimmed.startsWith("<>")

  private fun parseRequestLine(line: String): Pair<String, String> {
    val tokens = line.trim().split(Regex("\\s+"))
    if (tokens.isEmpty()) return "GET" to ""
    val first = tokens.first().uppercase()
    val method =
      when (first) {
        "GET",
        "HEAD",
        "POST",
        "PUT",
        "DELETE",
        "CONNECT",
        "PATCH",
        "OPTIONS",
        "TRACE",
        -> first
        else -> "GET"
      }
    val url =
      if (method == first && tokens.size >= 2) {
        tokens[1]
      } else {
        tokens[0]
      }
    return method to url
  }

  private fun parseHeader(line: String): Pair<String, String>? {
    val index = line.indexOf(":")
    if (index == -1) return null
    val key = line.substring(0, index).trim()
    val value = line.substring(index + 1).trim()
    return key to value
  }

  private fun resolveBody(bodyLines: List<String>, source: TemplateSource): String? {
    if (bodyLines.isEmpty()) return null
    val nonBlankLines = bodyLines.filter { it.isNotBlank() }
    if (nonBlankLines.size == 1) {
      val trimmed = nonBlankLines.first().trim()
      if (trimmed.startsWith("<") && !trimmed.startsWith("<>")) {
        val filePath = trimmed.removePrefix("<").trim()
        if (filePath.isNotBlank()) {
          val resolvedPath = source.resolveRelativePath(filePath)
          return if (File(resolvedPath).isAbsolute) readFileToString(File(resolvedPath))
          else readFileToString(resolvedPath)
        }
      }
    }
    return bodyLines.joinToString("\n").trimEnd()
  }

  private fun readScript(
    lines: List<String>,
    startIndex: Int,
    source: TemplateSource,
  ): Pair<String, Int> {
    val line = lines[startIndex].trim()
    val scriptSpec = line.drop(1).trim()
    return if (scriptSpec.startsWith("{%")) {
      readInlineScript(lines, startIndex)
    } else {
      val resolvedPath = source.resolveRelativePath(scriptSpec)
      val content =
        if (File(resolvedPath).isAbsolute) readFileToString(File(resolvedPath))
        else readFileToString(resolvedPath)
      content to (startIndex + 1)
    }
  }

  private fun readInlineScript(lines: List<String>, startIndex: Int): Pair<String, Int> {
    val firstLine = lines[startIndex]
    val start = firstLine.indexOf("{%")
    if (start == -1) return "" to (startIndex + 1)
    val afterStart = firstLine.substring(start + 2)
    val endIndexInLine = afterStart.indexOf("%}")
    if (endIndexInLine != -1) {
      val script = afterStart.substring(0, endIndexInLine).trim()
      return script to (startIndex + 1)
    }

    val scriptLines = mutableListOf<String>()
    if (afterStart.isNotBlank()) {
      scriptLines.add(afterStart.trimEnd())
    }
    var index = startIndex + 1
    while (index < lines.size) {
      val line = lines[index]
      val endIndex = line.indexOf("%}")
      if (endIndex != -1) {
        scriptLines.add(line.substring(0, endIndex).trimEnd())
        index++
        break
      } else {
        scriptLines.add(line)
        index++
      }
    }
    return scriptLines.joinToString("\n").trim() to index
  }

  private fun deriveName(method: String, url: String): String =
    "${method.trim()} ${url.trim()}".trim()
}
