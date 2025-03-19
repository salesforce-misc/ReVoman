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

internal fun postStepHookExe(kick: Kick, pm: PostmanSDK): PostStepHookFailure? =
  pickPostStepHooks(kick.postStepHooks(), pm.currentStepReport, pm.rundown)
    .map { postStepHook ->
      runCatching(pm.currentStepReport.step, POST_STEP_HOOK) {
          postStepHook.accept(pm.currentStepReport, pm.rundown)
        }
        .mapLeft { PostStepHookFailure(it, pm.currentStepReport.requestInfo!!.get(), pm.currentStepReport.responseInfo!!.get()) }
    }
    .firstOrNull { it.isLeft() }
    ?.leftOrNull()

private fun pickPostStepHooks(
  postStepHooks: List<HookConfig>,
  currentStepReport: StepReport,
  rundown: Rundown,
): Sequence<PostStepHook> =
  postStepHooks
    .asSequence()
    .filter { (it.pick as PostTxnStepPick).pick(currentStepReport, rundown) }
    .map { it.stepHook as PostStepHook }
    .also {
      if (it.iterator().hasNext()) {
        logger.info { "${currentStepReport.step} Picked Post hook count : ${it.count()}" }
        currentStepReport.step.postStepHookCount = it.count()
      }
    }

private val logger = KotlinLogging.logger {}
