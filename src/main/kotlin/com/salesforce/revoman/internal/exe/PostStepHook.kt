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
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.output.ExeType.POST_STEP_HOOK
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.HookFailure.PostStepHookFailure
import io.github.oshai.kotlinlogging.KotlinLogging

internal fun postHookExe(kick: Kick, pm: PostmanSDK): PostStepHookFailure? =
  pickPostHooks(kick.postHooks(), pm.currentStepReport, pm.rundown)
    .asSequence()
    .map { postHook ->
      runChecked(pm.currentStepReport.step, POST_STEP_HOOK) {
          postHook.accept(pm.currentStepReport, pm.rundown)
        }
        .mapLeft { PostStepHookFailure(it) }
    }
    .firstOrNull { it.isLeft() }
    ?.leftOrNull()

private fun pickPostHooks(
  postHooks: List<HookConfig>,
  currentStepReport: StepReport,
  rundown: Rundown,
): List<PostStepHook> =
  postHooks
    .asSequence()
    .filter { (it.pick as PostTxnStepPick).pick(currentStepReport, rundown) }
    .map { it.stepHook as PostStepHook }
    .toList()
    .also {
      if (it.isNotEmpty()) {
        logger.info { "${currentStepReport.step} Picked Post hook count : ${it.size}" }
        currentStepReport.step.postHookCount = it.size
      }
    }

private val logger = KotlinLogging.logger {}
