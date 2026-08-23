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
import org.junit.jupiter.api.Test

class RunLogContextActiveSinkTest {

  @Test
  fun `no sink bound - not active`() {
    assertThat(RunLogContext.hasActiveSink()).isFalse()
  }

  @Test
  fun `NoOp sink - not active`() {
    RunLogContext.where(RunLogSink.NoOp) { assertThat(RunLogContext.hasActiveSink()).isFalse() }
    assertThat(RunLogContext.hasActiveSink()).isFalse()
  }

  @Test
  fun `real sink - active`() {
    val sink =
      object : RunLogSink {
        override fun line(level: LogLevel, message: String) = Unit

        override fun event(event: StepEvent) = Unit

        override fun close() = Unit
      }
    RunLogContext.where(sink) { assertThat(RunLogContext.hasActiveSink()).isTrue() }
    assertThat(RunLogContext.hasActiveSink()).isFalse()
  }
}
