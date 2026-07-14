/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.salesforce.revoman.input.config.HookConfig
import com.salesforce.revoman.input.config.HookConfig.StepHook.PostStepHook
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.output.ExeType.POST_STEP_HOOK
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.HookFailure.PostStepHookFailure
import io.github.oshai.kotlinlogging.KotlinLogging

@JvmSynthetic
internal fun postStepHookExe(
  kick: Kick,
  currentStepReport: StepReport,
  rundown: Rundown,
): PostStepHookFailure? =
  // asSequence keeps hook execution LAZY + short-circuiting: if a picked hook fails, later hooks'
  // accept() (with their side effects) do NOT run — the pre-D2 Sequence behavior. D2 materialized
  // only the PICK (so the pick predicate runs once); execution order/short-circuit is preserved.
  pickPostStepHooks(kick.postStepHooks(), currentStepReport, rundown)
    .asSequence()
    .map { postStepHook ->
      runCatching(currentStepReport.step, POST_STEP_HOOK) {
          postStepHook.accept(currentStepReport, rundown)
        }
        .mapLeft {
          PostStepHookFailure(
            it,
            currentStepReport.requestInfo!!.get(),
            currentStepReport.responseInfo!!.get(),
          )
        }
    }
    .firstOrNull { it.isLeft() }
    ?.leftOrNull()

private fun pickPostStepHooks(
  postStepHooks: List<HookConfig>,
  currentStepReport: StepReport,
  rundown: Rundown,
): List<PostStepHook> =
  postStepHooks
    .filter { (it.pick as PostTxnStepPick).pick(currentStepReport, rundown) }
    .map { it.stepHook as PostStepHook }
    .also {
      if (it.isNotEmpty()) {
        logger.info { "${currentStepReport.step} Picked Post hook count : ${it.size}" }
        currentStepReport.step.postStepHookCount = it.size
      }
    }

private val logger = KotlinLogging.logger {}
