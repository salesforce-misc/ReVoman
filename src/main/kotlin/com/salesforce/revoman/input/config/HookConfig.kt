/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.HookConfig.StepHook.PostStepHook
import com.salesforce.revoman.input.config.HookConfig.StepHook.PreStepHook
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxnInfo
import org.http4k.core.Request

@ExposedCopyVisibility
data class HookConfig private constructor(val pick: StepPick, val stepHook: StepHook) {
  sealed interface StepHook {
    fun interface PreStepHook : StepHook {
      @Throws(Throwable::class)
      fun accept(currentStep: Step, requestInfo: TxnInfo<Request>, rundown: Rundown)
    }

    fun interface PostStepHook : StepHook {
      @Throws(Throwable::class) fun accept(currentStepReport: StepReport, rundown: Rundown)
    }
  }

  companion object {
    @JvmStatic fun pre(pick: PreTxnStepPick, hook: PreStepHook): HookConfig = HookConfig(pick, hook)

    @JvmStatic
    fun post(pick: PostTxnStepPick, hook: PostStepHook): HookConfig = HookConfig(pick, hook)
  }
}
