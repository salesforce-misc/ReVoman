/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import org.junit.jupiter.api.Test

class RequestToHttpRequestTest {
  private val moshi = initMoshi()

  private fun request(contentType: String?, rawBody: String): Request =
    Request(
      method = "POST",
      header = contentType?.let { listOf(Header(key = "Content-Type", value = it)) } ?: emptyList(),
      body = Body(mode = "raw", raw = rawBody),
    )

  @Test
  fun `comment-free JSON body with no content-type is pretty-printed byte-for-byte and content-type is set`() {
    val raw = """{"n":5,"id":1234567890123}"""
    val http = request(contentType = null, rawBody = raw).toHttpRequest(moshi)
    // Precision preserved: 5 stays 5 (not 5.0), large id keeps full precision.
    assertThat(http.bodyString()).contains("\"n\": 5")
    assertThat(http.bodyString()).contains("\"id\": 1234567890123")
    assertThat(http.bodyString()).doesNotContain("5.0")
    // http4k's APPLICATION_JSON carries a UTF-8 charset; the detection side effect still fires.
    assertThat(http.header("Content-Type")).isEqualTo("application/json; charset=utf-8")
  }

  @Test
  fun `comment-bearing JSON body is round-tripped so comments are stripped`() {
    val raw =
      """
      {
        // a line comment
        "a": 1
      }
      """
        .trimIndent()
    val http = request(contentType = "application/json", rawBody = raw).toHttpRequest(moshi)
    assertThat(http.bodyString()).doesNotContain("// a line comment")
    assertThat(http.bodyString()).contains("\"a\"")
  }

  @Test
  fun `malformed non-JSON body with no content-type is left unchanged and no content-type is added`() {
    // A '{'-leading but malformed body: JsonPretty's first-char heuristic would ACCEPT it, but it
    // fails isStrictJson (not strict JSON) AND the fallback round-trip's lenient Moshi parse
    // throws,
    // so the body lands in getOrDefault(rawBody) unchanged and the application/json detection side
    // effect does NOT fire. This proves the JSON gate is a Moshi parse, not JsonPretty's heuristic.
    val raw = """{"a":}"""
    val http = request(contentType = null, rawBody = raw).toHttpRequest(moshi)
    assertThat(http.bodyString()).isEqualTo(raw)
    assertThat(http.header("Content-Type")).isNull()
  }

  @Test
  fun `JSON5-lenient body with a single-quoted value is normalized not mangled`() {
    // Regression: JsonPretty only understands strict JSON; a single-quoted value with an interior
    // comma must NOT be split into structural pieces. The lenient body normalizes via round-trip.
    val raw = "{'k':'a,b'}"
    val http = request(contentType = null, rawBody = raw).toHttpRequest(moshi)
    // The value "a,b" must survive intact as a single JSON string value.
    assertThat(http.bodyString()).contains("\"k\"")
    assertThat(http.bodyString()).contains("a,b")
    // And it must be VALID strict JSON now (re-parseable), unlike the mangled single-quote output.
    assertThat(moshi.fromJson<Any>(http.bodyString())).isEqualTo(mapOf("k" to "a,b"))
    assertThat(http.header("Content-Type")).isEqualTo("application/json; charset=utf-8")
  }

  @Test
  fun `null moshiReVoman with a JSON body and no content-type still adds the content-type header`() {
    // Mirrors ReVoman.kt:394 toHttpRequest(null). Body passes through unchanged (no moshi to
    // render).
    val raw = """{"a":1}"""
    val http = request(contentType = null, rawBody = raw).toHttpRequest(null)
    assertThat(http.bodyString()).isEqualTo(raw)
    // http4k's APPLICATION_JSON carries a UTF-8 charset.
    assertThat(http.header("Content-Type")).isEqualTo("application/json; charset=utf-8")
  }
}
