/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package org.example.revoman.consumer;

import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.input.config.Phase;
import com.salesforce.revoman.input.config.Runbook;
import com.salesforce.revoman.output.log.LogLevel;
import com.salesforce.revoman.output.log.RunLogSink;
import com.salesforce.revoman.output.log.StepEvent;

public final class JavaRunLogSinkApiFixture implements RunLogSink {
  @Override
  public void line(LogLevel level, String message) {}

  @Override
  public void event(StepEvent event) {}

  @Override
  public void close() {}

  public Kick attachToKick(Kick.Builder builder) {
    return builder.runLogSink(this).off();
  }

  public Runbook attachToRunbook(Kick kick) {
    return Runbook.configure().runLogSink(this).step("consumer", Phase.ACT, kick).off();
  }
}
