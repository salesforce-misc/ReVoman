/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ****************************************************************************
 */
package com.salesforce.revoman.internal

import org.http4k.client.ApacheClient
import org.http4k.client.PreCannedApacheHttpClients.insecureApacheHttpClient
import org.http4k.core.Filter
import org.http4k.core.NoOp
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.filter.DebuggingFilters

internal fun prepareHttpClient(bearerToken: String?, insecureHttp: Boolean) =
  DebuggingFilters.PrintRequestAndResponse()
    .then(if (bearerToken.isNullOrEmpty()) Filter.NoOp else ClientFilters.BearerAuth(bearerToken))
    .then(if (insecureHttp) ApacheClient(client = insecureApacheHttpClient()) else ApacheClient())
