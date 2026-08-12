/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package org.example.revoman.consumer;

import com.salesforce.revoman.input.PostExeHook;
import com.salesforce.revoman.input.config.CustomDynamicVariableGenerator;
import com.salesforce.revoman.input.config.HookConfig;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick;
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick;

public final class JavaHooksPicksAndDynamicApiFixture {
  public HookConfig preHook() {
    return HookConfig.pre(
        PreTxnStepPick.beforeStepName("create"),
        (step, request, rundown) -> {
          rundown.mutableEnv.set("consumer.preHook", step.name);
          consume(step.name, request.httpMsg.getUri().getPath(), rundown.stepReports.size());
        });
  }

  public HookConfig postHook() {
    return HookConfig.post(
        PostTxnStepPick.afterStepName("create"),
        (report, rundown) -> {
          rundown.mutableEnv.set("consumer.postHook", report.step.name);
          consume(report.step.name, report.isSuccessful, rundown.stepReports.size());
        });
  }

  public CustomDynamicVariableGenerator dynamicVariable() {
    return (variableName, report, rundown) -> {
      rundown.mutableEnv.set("consumer.dynamicVariable", variableName);
      return variableName + ":" + report.step.name + ":" + rundown.stepReports.size();
    };
  }

  public PostExeHook postExecution() {
    return (current, rundowns) -> {
      current.mutableEnv.set("consumer.postExecution", rundowns.size());
      consume(current.stepReports.size(), rundowns.size());
    };
  }

  public Kick attach(Kick.Builder builder) {
    return builder
        .hook(preHook())
        .hook(postHook())
        .customDynamicVariableGenerator("consumer", dynamicVariable())
        .off();
  }

  private static void consume(Object... values) {
    int ignored = values.length;
  }
}
