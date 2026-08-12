/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package org.example.revoman.consumer

import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.output.log.LogLevel
import com.salesforce.revoman.output.log.RunLogSink
import com.salesforce.revoman.output.log.StepEvent

class KotlinRunLogSinkApiFixture : RunLogSink {
  override fun line(level: LogLevel, message: String) {}

  override fun event(event: StepEvent) {}

  override fun close() {}

  fun attachToKick(builder: Kick.Builder): Kick = builder.runLogSink(this).off()

  fun attachToRunbook(kick: Kick): Runbook =
    Runbook.configure().runLogSink(this).step("consumer", Phase.ACT, kick).off()
}
