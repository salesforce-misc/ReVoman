package com.salesforce.revoman.internal.exe

import arrow.core.Either
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.internal.postman.state.Item
import com.salesforce.revoman.internal.prepareHttpClient
import com.salesforce.revoman.output.report.ExeType.HTTP_REQUEST
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response

internal fun httpRequest(
  stepName: String,
  itemWithRegex: Item,
  httpRequest: Request,
  insecureHttp: Boolean
): Either<Throwable, Response> =
  runChecked(stepName, HTTP_REQUEST) {
    // * NOTE gopala.akshintala 06/08/22: Preparing httpClient for each step,
    // * as there can be intermediate auths
    val httpClient: HttpHandler =
      prepareHttpClient(
        pm.environment.getString(itemWithRegex.auth?.bearerTokenKeyFromRegex),
        insecureHttp,
      )
    httpClient(httpRequest)
  }
