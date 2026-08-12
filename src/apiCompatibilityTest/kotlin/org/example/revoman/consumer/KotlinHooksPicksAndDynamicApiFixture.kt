/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package org.example.revoman.consumer

import com.salesforce.revoman.input.PostExeHook
import com.salesforce.revoman.input.config.CustomDynamicVariableGenerator
import com.salesforce.revoman.input.config.HookConfig
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick

class KotlinHooksPicksAndDynamicApiFixture {
  val preHook =
    HookConfig.pre(PreTxnStepPick.beforeStepName("create")) { step, request, rundown ->
      rundown.mutableEnv.set("consumer.preHook", step.name)
      consume(step.name, request.httpMsg.uri.path, rundown.stepReports.size)
    }

  val postHook =
    HookConfig.post(PostTxnStepPick.afterStepName("create")) { report, rundown ->
      rundown.mutableEnv.set("consumer.postHook", report.step.name)
      consume(report.step.name, report.isSuccessful, rundown.stepReports.size)
    }

  val dynamicVariable = CustomDynamicVariableGenerator { variableName, report, rundown ->
    rundown.mutableEnv.set("consumer.dynamicVariable", variableName)
    "$variableName:${report.step.name}:${rundown.stepReports.size}"
  }

  val postExecution = PostExeHook { current, prior ->
    current.mutableEnv.set("consumer.postExecution", prior.size)
    consume(current.stepReports.size, prior.size)
  }

  fun attach(builder: Kick.Builder): Kick =
    builder
      .hook(preHook)
      .hook(postHook)
      .customDynamicVariableGenerator("consumer", dynamicVariable)
      .off()

  private fun consume(vararg values: Any?) {
    values.size
  }
}
