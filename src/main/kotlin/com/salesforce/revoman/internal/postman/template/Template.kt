/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template

import com.salesforce.revoman.internal.json.MoshiReVoman
import com.squareup.moshi.JsonClass
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.ContentType.Companion.Text
import org.http4k.core.Method
import org.http4k.core.Uri
import org.http4k.core.queryParametersEncoded
import org.http4k.core.with
import org.http4k.lens.Header.CONTENT_TYPE

@JsonClass(generateAdapter = true)
internal data class Template(val item: List<Item>, val auth: Auth?)

@JsonClass(generateAdapter = true)
data class Item(
  val name: String = "",
  val item: List<Item>? = null,
  val request: Request = Request(),
  val event: List<Event>? = null,
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
      body?.raw?.trim()?.let {
        when {
          it.isBlank() -> it
          else ->
            // ! TODO 15 Mar 2025 gopala.akshintala: Detect the right content type if absent
            when {
              contentTypeHeader?.value == null ||
                APPLICATION_JSON.value.equals(contentTypeHeader.value, true) -> {
                runCatching { moshiReVoman?.jsonToObjToPrettyJson(it) ?: it }
                  .onSuccess { if (contentTypeHeader == null) contentTypeHeader = APPLICATION_JSON }
                  .getOrDefault(it)
              }
              else -> it
            }
        }
      } ?: ""
    val request =
      org.http4k.core
        .Request(Method.valueOf(method), uri)
        .headers(header.map { it.key.trim() to it.value.trim() })
        .body(cleansedRawBody)
    return if (contentTypeHeader != null) request.with(CONTENT_TYPE of contentTypeHeader)
    else request
  }
}
