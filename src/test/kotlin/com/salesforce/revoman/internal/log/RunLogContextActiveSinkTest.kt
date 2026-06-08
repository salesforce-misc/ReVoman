/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.log

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.output.log.LogLevel
import com.salesforce.revoman.output.log.RunLogSink
import com.salesforce.revoman.output.log.StepEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class RunLogContextActiveSinkTest {
  @AfterEach fun cleanup() = RunLogContext.remove()

  @Test
  fun `no sink installed - not active`() {
    RunLogContext.remove()
    assertThat(RunLogContext.hasActiveSink()).isFalse()
  }

  @Test
  fun `NoOp sink - not active`() {
    RunLogContext.install(RunLogSink.NoOp)
    assertThat(RunLogContext.hasActiveSink()).isFalse()
  }

  @Test
  fun `real sink - active`() {
    RunLogContext.install(object : RunLogSink {
      override fun line(level: LogLevel, message: String) {}
      override fun event(event: StepEvent) {}
      override fun close() {}
    })
    assertThat(RunLogContext.hasActiveSink()).isTrue()
  }
}
