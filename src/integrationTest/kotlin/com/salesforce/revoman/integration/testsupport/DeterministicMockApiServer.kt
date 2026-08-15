/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.testsupport

import com.salesforce.revoman.testing.http.MockHttpServer

/** Temporary compatibility facade for integration tests pending their public-server migration. */
class DeterministicMockApiServer private constructor(private val server: MockHttpServer) :
  AutoCloseable {
  val baseUrl: String
    get() = server.baseUrl

  fun requestSignatures(): List<String> =
    server.requests().map { request ->
      buildString {
        append(request.method)
        append(' ')
        append(request.path)
        if (request.queryParameters.isNotEmpty()) {
          append('?')
          append(
            request.queryParameters.joinToString("&") { (name, value) ->
              if (value == null) name else "$name=$value"
            }
          )
        }
      }
    }

  fun hitCount(path: String): Int = server.requests().count { it.path == path }

  override fun close() = server.close()

  companion object {
    @JvmStatic
    fun start(): DeterministicMockApiServer =
      DeterministicMockApiServer(MockHttpServer.start(DeterministicMockApi()))
  }
}
