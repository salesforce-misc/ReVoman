/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

/** Logging policy for HTTP and environment logging. */
data class LoggingConfig(
  val logHttpRequests: Boolean = false,
  val logHttpResponses: Boolean = false,
  val logHttpBodies: Boolean = false,
  val maxBodyChars: Int = 2048,
  val logEnvValues: Boolean = false,
  val maxEnvValueChars: Int = 256,
  val redactHeaders: Set<String> =
    setOf("authorization", "cookie", "set-cookie", "proxy-authorization", "x-api-key"),
  val redactEnvKeys: Set<String> =
    setOf("password", "secret", "token", "apikey", "api-key", "bearer", "session"),
) {
  fun shouldRedactHeader(name: String): Boolean = redactHeaders.any { it.equals(name, true) }

  fun shouldRedactEnvKey(key: String): Boolean =
    redactEnvKeys.any { key.contains(it, ignoreCase = true) }
}
