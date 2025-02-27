/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.salesforce.revoman.input.config.HookConfig
import com.salesforce.revoman.input.config.HookConfig.StepHook.PreStepHook
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.output.ExeType.PRE_STEP_HOOK
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.failure.HookFailure.PreStepHookFailure
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.core.Request

internal fun preHookExe(
  currentStep: Step,
  kick: Kick,
  requestInfo: TxnInfo<Request>,
  pm: PostmanSDK,
): PreStepHookFailure? =
  pickPreHooks(kick.preHooks(), currentStep, requestInfo, pm.rundown)
    .map { preHook ->
      runChecked(currentStep, PRE_STEP_HOOK) {
          preHook.accept(currentStep, requestInfo, pm.rundown)
        }
        .mapLeft { PreStepHookFailure(it, requestInfo) }
    }
    .firstOrNull { it.isLeft() }
    ?.leftOrNull()

private fun pickPreHooks(
  preHooks: List<HookConfig>,
  currentStep: Step,
  requestInfo: TxnInfo<Request>,
  rundown: Rundown,
): Sequence<PreStepHook> =
  preHooks
    .asSequence()
    .filter { (it.pick as PreTxnStepPick).pick(currentStep, requestInfo, rundown) }
    .map { it.stepHook as PreStepHook }
    .also {
      if (it.iterator().hasNext()) {
        logger.info { "$currentStep Picked Pre hook count : ${it.count()}" }
        currentStep.preHookCount = it.count()
      }
    }

private val logger = KotlinLogging.logger {}
