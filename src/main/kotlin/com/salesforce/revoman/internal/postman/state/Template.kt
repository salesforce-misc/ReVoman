/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.internal.postman.state

import com.squareup.moshi.JsonClass
import org.http4k.core.ContentType
import org.http4k.core.Method
import org.http4k.core.Uri
import org.http4k.core.queryParametersEncoded
import org.http4k.core.with
import org.http4k.lens.Header.CONTENT_TYPE

@JsonClass(generateAdapter = true)
internal data class Template(val item: List<Item>, val auth: Auth?)

@JsonClass(generateAdapter = true)
internal data class Item(
  val name: String = "",
  val item: List<Item>?,
  val request: Request = Request(),
  val auth: Auth?,
  val event: List<Event>? = null
)

@JsonClass(generateAdapter = true) internal data class Script(val exec: List<String>)

@JsonClass(generateAdapter = true) internal data class Header(val key: String, val value: String)

@JsonClass(generateAdapter = true) internal data class Url(val raw: String = "")

@JsonClass(generateAdapter = true) internal data class Body(val mode: String, val raw: String)

@JsonClass(generateAdapter = true)
internal data class Event(val listen: String, val script: Script)

@JsonClass(generateAdapter = true)
internal data class Request(
  val method: String = "",
  val header: List<Header> = emptyList(),
  val url: Url = Url(),
  val body: Body? = null,
  val event: List<Event>? = null
) {
  internal fun toHttpRequest(): org.http4k.core.Request {
    val contentType =
      header
        .firstOrNull {
          it.key.equals(CONTENT_TYPE.meta.name, ignoreCase = true)
        }
        ?.value
        ?.let { ContentType.Text(it) }
        ?: ContentType.APPLICATION_JSON
    val uri = Uri.of(url.raw).queryParametersEncoded()
    return org.http4k.core
      .Request(Method.valueOf(method), uri)
      .with(CONTENT_TYPE of contentType)
      .headers(header.map { it.key to it.value })
      .body(body?.raw ?: "")
  }
}
