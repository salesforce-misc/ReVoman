/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template

import com.salesforce.revoman.internal.postman.PostmanSDK
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
  internal fun toHttpRequest(): org.http4k.core.Request {
    val contentType =
      header
        .firstOrNull { it.key.equals(CONTENT_TYPE.meta.name, ignoreCase = true) }
        ?.value
        ?.let { Text(it) } ?: APPLICATION_JSON
    val uri = Uri.of(url.raw.trim()).queryParametersEncoded()
    return org.http4k.core
      .Request(Method.valueOf(method), uri)
      .with(CONTENT_TYPE of contentType)
      .headers(header.map { it.key.trim() to it.value.trim() })
      .body(body?.raw?.trim() ?: "")
  }

  internal fun toPMSDKRequest(pm: PostmanSDK): PostmanSDK.Request = pm.from(this)
}
