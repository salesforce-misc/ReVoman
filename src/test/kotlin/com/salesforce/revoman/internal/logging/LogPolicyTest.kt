/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.logging

import com.salesforce.revoman.input.config.LoggingConfig
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class LogPolicyTest {
  @AfterEach
  fun reset() {
    LogPolicy.update(LoggingConfig())
  }

  @Test
  fun `env values are omitted by default`() {
    LogPolicy.update(LoggingConfig())
    LogPolicy.formatEnvValue("token", "secret") shouldBe "<omitted>"
  }

  @Test
  fun `env values are redacted for sensitive keys`() {
    LogPolicy.update(LoggingConfig(logEnvValues = true))
    LogPolicy.formatEnvValue("authToken", "secret") shouldBe "<redacted>"
  }

  @Test
  fun `env values are truncated when max length is set`() {
    LogPolicy.update(LoggingConfig(logEnvValues = true, maxEnvValueChars = 4))
    LogPolicy.formatEnvValue("safeKey", "123456") shouldBe "1234...(truncated)"
  }

  @Test
  fun `body is truncated when max length is set`() {
    LogPolicy.update(LoggingConfig(logHttpBodies = true, maxBodyChars = 5))
    LogPolicy.formatBody("abcdef") shouldBe "abcde...(truncated)"
  }
}
