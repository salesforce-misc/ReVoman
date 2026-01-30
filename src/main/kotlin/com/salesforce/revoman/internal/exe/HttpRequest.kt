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
import com.salesforce.revoman.internal.logging.LogPolicy
import com.salesforce.revoman.output.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import org.apache.hc.client5.http.socket.ConnectionSocketFactory
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory.INSTANCE
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory
import org.apache.hc.core5.http.config.RegistryBuilder
import org.apache.hc.core5.ssl.SSLContextBuilder
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import io.github.oshai.kotlinlogging.KotlinLogging

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
      logHttpRequest(currentStep, httpRequest)
      prepareHttpClient(insecureHttp)(httpRequest).also { response ->
        logHttpResponse(currentStep, response)
      }
    }
    .mapLeft { HttpRequestFailure(it, TxnInfo(httpMsg = httpRequest, moshiReVoman = moshiReVoman)) }
    .map { TxnInfo(httpMsg = it, moshiReVoman = moshiReVoman) }

private fun prepareHttpClient(insecureHttp: Boolean): HttpHandler =
  if (insecureHttp) insecureApacheClient else secureApacheClient

private fun logHttpRequest(currentStep: Step, httpRequest: Request) {
  if (!LogPolicy.shouldLogHttpRequests()) return
  val headers =
    httpRequest.headers
      .joinToString { (name, value) ->
        val sanitized = if (LogPolicy.shouldRedactHeader(name)) "<redacted>" else value
        "$name=$sanitized"
      }
  val body =
    if (LogPolicy.shouldLogHttpBodies()) LogPolicy.formatBody(httpRequest.bodyString())
    else "<omitted>"
  logger.info {
    "$currentStep HTTP request: method=${httpRequest.method} uri=${httpRequest.uri} headers=[$headers] body=$body"
  }
}

private fun logHttpResponse(currentStep: Step, response: Response) {
  if (!LogPolicy.shouldLogHttpResponses()) return
  val headers =
    response.headers.joinToString { (name, value) ->
      val sanitized = if (LogPolicy.shouldRedactHeader(name)) "<redacted>" else value
      "$name=$sanitized"
    }
  val body =
    if (LogPolicy.shouldLogHttpBodies()) LogPolicy.formatBody(response.bodyString())
    else "<omitted>"
  logger.info {
    "$currentStep HTTP response: status=${response.status} headers=[$headers] body=$body"
  }
}

/** Only for Testing. DO NOT USE IN PROD */
private fun insecureApacheHttpClient(): CloseableHttpClient =
  SSLContextBuilder()
    .loadTrustMaterial(null) { _, _ -> true }
    .build()
    .run {
      HttpClientBuilder.create()
        .setConnectionManager(
          PoolingHttpClientConnectionManager(
            RegistryBuilder.create<ConnectionSocketFactory>()
              .register("http", INSTANCE)
              .register("https", SSLConnectionSocketFactory(this) { _, _ -> true })
              .build()
          )
        )
        .build()
    }

private val secureApacheClient: HttpHandler = ApacheClient()

private val insecureApacheClient: HttpHandler = ApacheClient(client = insecureApacheHttpClient())

private val logger = KotlinLogging.logger {}
