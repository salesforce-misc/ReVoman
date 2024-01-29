/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.salesforce.revoman.input.config.HookConfig
import com.salesforce.revoman.input.config.HookConfig.Hook.PostHook
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.output.ExeType.POST_HOOK
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.HookFailure.PostHookFailure
import io.github.oshai.kotlinlogging.KotlinLogging

internal fun postHookExe(
  currentStepReport: StepReport,
  kick: Kick,
  stepReports: List<StepReport>
): PostHookFailure? =
  pickPostHooks(
      kick.postHooks(),
      currentStepReport,
      Rundown(stepReports, pm.environment, kick.haltOnFailureOfTypeExcept())
    )
    .asSequence()
    .map { postHook ->
      runChecked(currentStepReport.step, POST_HOOK) {
          postHook.accept(
            currentStepReport,
            Rundown(stepReports, pm.environment, kick.haltOnFailureOfTypeExcept())
          )
        }
        .mapLeft { PostHookFailure(it) }
    }
    .firstOrNull { it.isLeft() }
    ?.leftOrNull()

private fun pickPostHooks(
  postHooks: List<HookConfig>,
  currentStepReport: StepReport,
  rundown: Rundown
): List<PostHook> =
  postHooks
    .asSequence()
    .filter { (it.pick as PostTxnStepPick).pick(currentStepReport, rundown) }
    .map { it.hook as PostHook }
    .toList()
    .also {
      if (it.isNotEmpty()) {
        logger.info { "${currentStepReport.step} Picked Post hook count : ${it.size}" }
        currentStepReport.step.postHookCount = it.size
      }
    }

private val logger = KotlinLogging.logger {}
