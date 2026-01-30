/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.logging

import com.salesforce.revoman.input.config.LoggingConfig

internal object LogPolicy {
  @Volatile private var config: LoggingConfig = LoggingConfig()

  fun update(loggingConfig: LoggingConfig) {
    config = loggingConfig
  }

  fun shouldLogHttpRequests(): Boolean = config.logHttpRequests

  fun shouldLogHttpResponses(): Boolean = config.logHttpResponses

  fun shouldLogHttpBodies(): Boolean = config.logHttpBodies

  fun maxBodyChars(): Int = config.maxBodyChars.coerceAtLeast(0)

  fun maxEnvValueChars(): Int = config.maxEnvValueChars.coerceAtLeast(0)

  fun shouldRedactHeader(name: String): Boolean = config.shouldRedactHeader(name)

  fun shouldRedactEnvKey(key: String): Boolean = config.shouldRedactEnvKey(key)

  fun formatEnvValue(key: String, value: Any?): String {
    if (!config.logEnvValues) return "<omitted>"
    if (shouldRedactEnvKey(key)) return "<redacted>"
    val asString = value?.toString() ?: "null"
    val limit = maxEnvValueChars()
    return if (limit > 0 && asString.length > limit) {
      "${asString.take(limit)}...(truncated)"
    } else {
      asString
    }
  }

  fun formatBody(body: String): String {
    if (!shouldLogHttpBodies()) return "<omitted>"
    val limit = maxBodyChars()
    return if (limit > 0 && body.length > limit) {
      "${body.take(limit)}...(truncated)"
    } else {
      body
    }
  }
}
