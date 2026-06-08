/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either
import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.output.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.json.JsonPretty
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier
import org.apache.hc.core5.ssl.SSLContextBuilder
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.core.Response

@JvmSynthetic
internal fun fireHttpRequest(
  currentStep: Step,
  httpRequest: Request,
  insecureHttp: Boolean,
  moshiReVoman: MoshiReVoman,
): Either<HttpRequestFailure, TxnInfo<Response>> =
  runCatching(currentStep, HTTP_REQUEST) {
      // * NOTE gopala.akshintala 06/08/22: Preparing httpClient for each step,
      // * as there can be intermediate auths
      // ! TODO 29/01/24 gopala.akshintala: When would bearer token size be > 1?
      prepareHttpClient(insecureHttp)(httpRequest)
    }
    .mapLeft { HttpRequestFailure(it, TxnInfo(httpMsg = httpRequest, moshiReVoman = moshiReVoman)) }
    .map { TxnInfo(httpMsg = it, moshiReVoman = moshiReVoman) }

/**
 * Render an http4k [HttpMessage] for the run log: the wire text with `\r\n` normalized to `\n`, and
 * the body (everything after the first blank line) pretty-printed via [JsonPretty] when it is JSON
 * (non-JSON bodies pass through unchanged). The request/status line and headers are left as-is.
 * Single source of truth for the http4k message shape, so any
 * [com.salesforce.revoman.output.log.RunLogSink] consumer receives an already-beautified exchange.
 */
@JvmSynthetic
internal fun renderHttpMsg(httpMsg: HttpMessage): String {
  val normalized = httpMsg.toString().replace("\r\n", "\n")
  val sep = normalized.indexOf("\n\n")
  if (sep < 0) return normalized // no body
  val head = normalized.substring(0, sep + 2)
  val body = normalized.substring(sep + 2)
  return head + JsonPretty.pretty(body)
}

internal fun prepareHttpClient(insecureHttp: Boolean): HttpHandler =
  if (insecureHttp) ApacheClient(client = insecureApacheHttpClient()) else ApacheClient()

/** WARNING: Only for Testing. DO NOT USE IN PROD */
private fun insecureApacheHttpClient(): CloseableHttpClient =
  SSLContextBuilder()
    .loadTrustMaterial(null) { _, _ -> true }
    .build()
    .let { sslContext ->
      HttpClientBuilder.create()
        .setConnectionManager(
          PoolingHttpClientConnectionManagerBuilder.create()
            .setTlsSocketStrategy(
              ClientTlsStrategyBuilder.create()
                .setSslContext(sslContext)
                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .buildClassic()
            )
            .build()
        )
        .build()
    }
