/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.http

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.github.oshai.kotlinlogging.KotlinLogging

internal object HttpClientEnvParser {

  @OptIn(ExperimentalStdlibApi::class)
  private val envAdapter = Moshi.Builder().build().adapter<Map<String, Map<String, Any?>>>()

  fun parseEnv(envFileContent: String, envName: String? = null): Map<String, Any?> {
    val allEnvs = envAdapter.fromJson(envFileContent) ?: return emptyMap()
    val selectedEnv =
      when {
        envName != null ->
          allEnvs[envName].also { env ->
            if (env == null)
              logger.warn {
                "Environment '$envName' not found in http-client.env.json. Available: ${allEnvs.keys}"
              }
          }
        else -> allEnvs.values.firstOrNull()
      }
    return selectedEnv ?: emptyMap()
  }
}

private val logger = KotlinLogging.logger {}
