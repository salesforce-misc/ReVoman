/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.json

/**
 * Re-indents already-valid JSON to a pretty, multi-line form WITHOUT reparsing values — a
 * whitespace-only transform. Unlike a parse→model→serialize round-trip (e.g. Moshi, whose
 * `BigDecimalAdapter` historically collapsed numbers to `Double`), this copies every
 * string/number/literal byte-for-byte, so `5` stays `5` (not `5.0`) and a large numeric id keeps
 * full precision — the guarantee a verbatim run-log needs.
 *
 * Structural tokens `{ } [ ] , :` outside string literals drive indentation; everything inside a
 * string literal (quotes + `\`-escapes respected) is emitted unchanged. Input that is not
 * well-formed JSON (an HTML error body, an empty/blank string, a bare token) is returned unchanged
 * — best-effort, since an HTTP body may not be JSON at all.
 *
 * Public, library-owned API: used internally by ReVoman (HTTP request/response body rendering) and
 * consumed by external sinks (e.g. Core's per-test run log).
 */
object JsonPretty {
  @JvmStatic
  @JvmOverloads
  fun pretty(json: String, indent: String = "  "): String {
    val trimmed = json.trim()
    if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) return json
    return try {
      reindent(trimmed, indent)
    } catch (e: RuntimeException) {
      json // malformed — best-effort passthrough
    }
  }

  private fun reindent(json: String, indent: String): String {
    val sb = StringBuilder(json.length + json.length / 4)
    var depth = 0
    var i = 0
    val n = json.length
    while (i < n) {
      val c = json[i]
      when (c) {
        '"' -> {
          val end = endOfString(json, i)
          sb.append(json, i, end + 1)
          i = end + 1
          continue
        }
        '{', '[' -> {
          val next = nextNonWs(json, i + 1)
          if (next < n && ((c == '{' && json[next] == '}') || (c == '[' && json[next] == ']'))) {
            sb.append(c).append(json[next])
            i = next + 1
            continue
          }
          depth++
          sb.append(c).append('\n').append(indent.repeat(depth))
        }
        '}', ']' -> {
          depth--
          sb.append('\n').append(indent.repeat(depth)).append(c)
        }
        ',' -> sb.append(",\n").append(indent.repeat(depth))
        ':' -> sb.append(": ")
        ' ', '\t', '\n', '\r' -> {} // drop existing insignificant whitespace
        else -> sb.append(c)
      }
      i++
    }
    return sb.toString()
  }

  /** Index of the closing quote of the string literal that starts at `start` ('"'). */
  private fun endOfString(json: String, start: Int): Int {
    var i = start + 1
    while (i < json.length) {
      when (json[i]) {
        '\\' -> i += 2 // skip the escaped char
        '"' -> return i
        else -> i++
      }
    }
    throw IllegalStateException("unterminated string at $start")
  }

  private fun nextNonWs(json: String, from: Int): Int {
    var i = from
    while (i < json.length && json[i].isWhitespace()) i++
    return i
  }
}
