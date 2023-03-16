package org.revcloud.revoman.internal

import org.http4k.client.ApacheClient
import org.http4k.client.PreCannedApacheHttpClients.insecureApacheHttpClient
import org.http4k.core.Filter
import org.http4k.core.NoOp
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.filter.DebuggingFilters

// ! TODO gopala.akshintala 28/01/23: Use auth type from the collection
internal fun prepareHttpClient(bearerToken: String?, insecureHttp: Boolean?) = DebuggingFilters.PrintRequestAndResponse()
  .then(if (bearerToken.isNullOrEmpty()) Filter.NoOp else ClientFilters.BearerAuth(bearerToken))
  .then(if (insecureHttp == true) ApacheClient(client = insecureApacheHttpClient()) else ApacheClient())
