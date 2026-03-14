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
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.then
import org.http4k.filter.DebuggingFilters

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

internal fun prepareHttpClient(insecureHttp: Boolean): HttpHandler =
  DebuggingFilters.PrintRequestAndResponse()
    .then(if (insecureHttp) ApacheClient(client = insecureApacheHttpClient()) else ApacheClient())

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
