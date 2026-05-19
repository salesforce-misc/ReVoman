/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.salesforce.revoman.internal.postman.template.Auth
import com.salesforce.revoman.internal.postman.template.Body
import com.salesforce.revoman.internal.postman.template.Event
import com.salesforce.revoman.internal.postman.template.Header
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import io.github.oshai.kotlinlogging.KotlinLogging

internal object V3ToV2Converter {
  fun toItem(v3: V3Request, fallbackName: String, inheritedAuth: Auth?): Item {
    val effectiveAuth = if (v3.auth.isNotEmpty()) toAuth(v3.auth) else inheritedAuth
    return Item(
      name = v3.name ?: fallbackName,
      item = null,
      request = toRequest(v3, effectiveAuth),
      event = toEvents(v3.scripts).takeIf { it.isNotEmpty() },
    )
  }

  fun toAuth(authList: List<V3Auth>): Auth? {
    val first = authList.firstOrNull() ?: return null
    if (first.type != "bearer") {
      logger.warn {
        "v3 auth type '${first.type}' not supported; dropping. Only 'bearer' is supported."
      }
      return null
    }
    val token = first.credentials["token"] ?: ""
    return Auth(
      type = "bearer",
      bearer = listOf(Auth.Bearer(key = first.name ?: "token", type = "bearer", value = token)),
    )
  }

  private fun toRequest(v3: V3Request, auth: Auth?): Request =
    Request(
      auth = auth,
      method = v3.method,
      header = v3.headers.entries.map { (k, v) -> Header(key = k, value = v) },
      url = Url(raw = mergeQueryParams(v3.url, v3.queryParams)),
      body = toBody(v3.body),
      event = null,
    )

  internal fun mergeQueryParams(url: String, queryParams: Map<String, String>): String {
    if (queryParams.isEmpty()) return url
    val separator = if (url.contains("?")) "&" else "?"
    val extra = queryParams.entries.joinToString("&") { (k, v) -> "$k=$v" }
    return "$url$separator$extra"
  }

  private fun toBody(v3body: V3Body?): Body? {
    if (v3body == null) return null
    return Body(mode = "raw", raw = v3body.content)
  }

  private fun toEvents(scripts: List<V3Script>): List<Event> {
    val byListen =
      scripts.groupBy { script ->
        when (script.type) {
          "afterResponse" -> "test"
          "beforeRequest",
          "prerequest" -> "prerequest"
          else -> {
            logger.warn { "v3 script type '${script.type}' not recognized; skipping." }
            null
          }
        }
      }
    return byListen.entries
      .filter { it.key != null }
      .map { (listen, scripts) ->
        val combinedExec = scripts.flatMap { it.code.lines() }
        Event(listen = listen!!, script = Event.Script(exec = combinedExec))
      }
  }
}

private val logger = KotlinLogging.logger {}
