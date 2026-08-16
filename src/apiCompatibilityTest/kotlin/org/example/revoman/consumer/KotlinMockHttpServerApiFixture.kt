/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package org.example.revoman.consumer

import com.salesforce.revoman.testing.http.MockHttpHandler
import com.salesforce.revoman.testing.http.MockHttpServer
import com.salesforce.revoman.testing.http.RecordedHttpRequest
import com.salesforce.revoman.testing.http.RecordedNameValue
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK

fun consumeMockHttpServerFromKotlin(existing: HttpHandler) {
  val adapted: MockHttpHandler = MockHttpHandler(existing)
  MockHttpServer.start(adapted).use { server ->
    val baseUrl: String = server.baseUrl
    val requests: List<RecordedHttpRequest> = server.requests()
    requests.forEach { request ->
      val method: Method = request.method
      val path: String = request.path
      val query: List<RecordedNameValue> = request.queryParameters
      val headers: List<RecordedNameValue> = request.headers
      val bytes: ByteArray = request.bodyBytes()
      val utf8: String = request.bodyString()
      val utf16: String = request.bodyString(Charsets.UTF_16)
      listOf(baseUrl, method, path, query, headers, bytes, utf8, utf16)
    }
  }
  MockHttpServer.start { Response(OK) }.close()
}
