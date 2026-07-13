/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template

import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.output.json.JsonPretty
import com.squareup.moshi.JsonClass
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.regex.Pattern
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.ContentType.Companion.Text
import org.http4k.core.Method
import org.http4k.core.Uri
import org.http4k.core.queryParametersEncoded
import org.http4k.core.with
import org.http4k.lens.Header.CONTENT_TYPE
import org.http4k.lens.bearerAuth

@JsonClass(generateAdapter = true)
internal data class Template(val item: List<Item>, val auth: Auth?)

@JsonClass(generateAdapter = true)
data class Item(
  val name: String = "",
  val item: List<Item>? = null,
  val request: Request = Request(),
  val event: List<Event>? = null,
  /**
   * Transient (not part of the Postman schema) sha256 of this item's `.request.yaml` source,
   * populated for v3-loaded leaf items. Carried forward to [Step.sourceHash]. "" when unknown.
   */
  @field:[com.squareup.moshi.Json(ignore = true)]
  @JvmField
  val sourceHash: String = "",
) {
  @JvmField val httpMethod = request.method
}

@JsonClass(generateAdapter = true)
data class Event(val listen: String, val script: Script) {
  @JsonClass(generateAdapter = true) data class Script(val exec: List<String>)
}

@JsonClass(generateAdapter = true) data class Header(val key: String, val value: String)

@JsonClass(generateAdapter = true) data class Url(val raw: String = "")

@JsonClass(generateAdapter = true) data class Body(val mode: String, val raw: String)

@JsonClass(generateAdapter = true)
data class Request(
  @JvmField val auth: Auth? = null,
  @JvmField val method: String = "",
  @JvmField val header: List<Header> = emptyList(),
  @JvmField val url: Url = Url(),
  @JvmField val body: Body? = null,
  @JvmField val event: List<Event>? = null,
) {
  internal fun toHttpRequest(moshiReVoman: MoshiReVoman?): org.http4k.core.Request {
    val uri = Uri.of(url.raw.trim()).queryParametersEncoded()
    var contentTypeHeader =
      header.firstOrNull { CONTENT_TYPE.meta.name.equals(it.key, true) }?.value?.let { Text(it) }
    val cleansedRawBody =
      body?.raw?.trim()?.let { rawBody ->
        when {
          rawBody.isBlank() -> rawBody
          else -> {
            // ! TODO 15 Mar 2025 gopala.akshintala: Detect the right content type if absent
            val hasComments = containsComments(rawBody)
            when {
              contentTypeHeader?.value == null ||
                (APPLICATION_JSON.value.equals(contentTypeHeader.value, true) && hasComments) ->
                runCatching {
                    when {
                      // JSON5 comment stripping needs the Moshi round-trip.
                      hasComments -> moshiReVoman?.jsonToObjToPrettyJson(rawBody, true) ?: rawBody
                      // Comment-free: validate as JSON (drives detection), render precision-safe.
                      else ->
                        moshiReVoman?.let { m ->
                          m.fromJson<Any>(rawBody)
                          JsonPretty.pretty(rawBody)
                        } ?: rawBody
                    }
                  }
                  .onSuccess {
                    if (contentTypeHeader == null) {
                      logger.info {
                        "Detected JSON Content type, adding $APPLICATION_JSON as content-type Header"
                      }
                      contentTypeHeader = APPLICATION_JSON
                    }
                  }
                  .getOrDefault(rawBody)
              else -> rawBody
            }
          }
        }
      } ?: ""
    val request =
      org.http4k.core
        .Request(Method.valueOf(method), uri)
        .headers(header.map { it.key.trim() to it.value.trim() })
        .body(cleansedRawBody)
    val requestWithAuth =
      auth?.bearer?.firstOrNull()?.value?.let {
        // * NOTE 11 May 2025 gopala.akshintala: Auth in headers will be overridden by Auth from
        // request, to align with Postman app behavior
        request.removeHeader("Authorization").bearerAuth(it)
      } ?: request
    return contentTypeHeader?.let { requestWithAuth.with(CONTENT_TYPE of it) } ?: requestWithAuth
  }

  companion object {
    private val COMMENT_PATTERN = Pattern.compile("//.*|/\\*[\\s\\S]*?\\*/")

    // * NOTE 24 Mar 2025 gopala.akshintala: This may give false positives when a string like
    // "https://..." is present in JSON key or value
    fun containsComments(str: String): Boolean =
      COMMENT_PATTERN.matcher(str).find().also {
        if (it) logger.info { "String may contain Comments" }
      }
  }
}

private val logger = KotlinLogging.logger {}
