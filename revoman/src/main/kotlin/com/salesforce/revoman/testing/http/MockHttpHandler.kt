/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.testing.http

import org.http4k.core.Request
import org.http4k.core.Response

/** Handles an incoming HTTP request and returns the response that should be observed by ReVoman. */
fun interface MockHttpHandler {
  /** Handles [request]. */
  @Throws(Exception::class) fun handle(request: Request): Response
}
