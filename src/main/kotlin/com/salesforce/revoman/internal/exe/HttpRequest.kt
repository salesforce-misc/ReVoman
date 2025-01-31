/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either
import com.salesforce.revoman.internal.postman.template.Auth
import com.salesforce.revoman.output.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import org.http4k.client.ApacheClient
import org.http4k.client.PreCannedApacheHttpClients.insecureApacheHttpClient
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.NoOp
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.filter.DebuggingFilters

internal fun fireHttpRequest(
  currentStep: Step,
  auth: Auth?,
  httpRequest: Request,
  insecureHttp: Boolean,
): Either<HttpRequestFailure, TxnInfo<Response>> =
  runChecked(currentStep, HTTP_REQUEST) {
      // * NOTE gopala.akshintala 06/08/22: Preparing httpClient for each step,
      // * as there can be intermediate auths
      // ! TODO 29/01/24 gopala.akshintala: When would bearer token size be > 1?
      prepareHttpClient(auth?.bearer?.firstOrNull()?.value, insecureHttp)(httpRequest)
    }
    .mapLeft { HttpRequestFailure(it, TxnInfo(httpMsg = httpRequest)) }
    .map { TxnInfo(httpMsg = it) }

private fun prepareHttpClient(bearerToken: String?, insecureHttp: Boolean): HttpHandler =
  DebuggingFilters.PrintRequestAndResponse()
    .then(if (bearerToken.isNullOrEmpty()) Filter.NoOp else ClientFilters.BearerAuth(bearerToken))
    .then(
      if (insecureHttp) ApacheClient(client = insecureApacheHttpClient())
      else ApacheClient()
    )
