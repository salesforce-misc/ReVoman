/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
@file:Suppress("InvisibleCharacter")

package com.salesforce.revoman.internal.exe

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import com.salesforce.revoman.input.config.HookConfig
import com.salesforce.revoman.input.config.HookConfig.Hook.PostHook
import com.salesforce.revoman.input.config.HookConfig.Hook.PreHook
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.RequestConfig
import com.salesforce.revoman.input.config.ResponseConfig
import com.salesforce.revoman.input.config.StepPick.ExeStepPick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.internal.postman.state.Item
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.ExeType
import com.salesforce.revoman.output.report.Folder
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxInfo
import io.exoquery.pprint
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.HttpMessage
import org.http4k.core.Request

internal fun isJson(httpMessage: HttpMessage) =
  httpMessage.bodyString().isNotBlank() &&
    httpMessage.header("content-type")?.let {
      logger.info { "content-type: $it, ${pprint(httpMessage)}" }
      val contentType = it.split(";")
      contentType.size > 1 && contentType[0].trim().equals(APPLICATION_JSON.value, true)
    } == true

internal fun deepFlattenItems(
  items: List<Item>,
  parentFolder: Folder? = null,
  stepIndexFromParent: String = "",
): List<Pair<Step, Item>> =
  items
    .asSequence()
    .flatMapIndexed { itemIndex, item ->
      val stepIndex =
        if (stepIndexFromParent.isBlank()) "${itemIndex + 1}"
        else "$stepIndexFromParent.${itemIndex + 1}"
      item.item
        ?.map { childItem -> childItem.copy(auth = childItem.auth ?: item.auth) }
        ?.let {
          val currentFolder = Folder(item.name, parentFolder)
          parentFolder?.subFolders?.add(currentFolder)
          deepFlattenItems(it, currentFolder, stepIndex)
        }
        ?: listOf(
          Step(stepIndex, item.name, item.request, parentFolder) to item.copy(auth = item.auth)
        )
    }
    .toList()

internal fun pickPreHooks(
  preHooks: List<HookConfig>,
  currentStep: Step,
  requestInfo: TxInfo<Request>,
  rundown: Rundown
): List<PreHook> =
  preHooks
    .asSequence()
    .filter { (it.pick as PreTxnStepPick).pick(currentStep, requestInfo, rundown) }
    .map { it.hook as PreHook }
    .toList()

internal fun pickPostHooks(
  postHooks: List<HookConfig>,
  currentStepReport: StepReport,
  rundown: Rundown
): List<PostHook> =
  postHooks
    .asSequence()
    .filter { (it.pick as PostTxnStepPick).pick(currentStepReport, rundown) }
    .map { it.hook as PostHook }
    .toList()

internal fun pickRequestConfig(
  requestConfigs: Set<RequestConfig>,
  currentStep: Step,
  currentRequestInfo: TxInfo<Request>,
  rundown: Rundown
): RequestConfig? =
  requestConfigs.firstOrNull { it.preTxnStepPick.pick(currentStep, currentRequestInfo, rundown) }

internal fun pickResponseConfig(
  pickToResponseConfig: List<ResponseConfig>,
  currentStepReport: StepReport,
  rundown: Rundown
): ResponseConfig? =
  pickToResponseConfig.firstOrNull { it.postTxnStepPick.pick(currentStepReport, rundown) }

internal fun shouldStepBePicked(
  currentStep: Step,
  runOnlySteps: List<ExeStepPick>,
  skipSteps: List<ExeStepPick>
): Boolean {
  if (runOnlySteps.isEmpty() && skipSteps.isEmpty()) {
    return true
  }
  val runStep = runOnlySteps.isEmpty() || runOnlySteps.any { it.pick(currentStep) }
  val skipStep = skipSteps.isNotEmpty() && skipSteps.any { it.pick(currentStep) }
  check(!(runStep && skipStep)) {
    "‼️😵‍💫 Ambiguous - $currentStep is picked for both run and skip execution"
  }
  if (skipStep) {
    logger.info { "$currentStep skipped for execution" }
  }
  return runStep
}

internal fun <T> runChecked(
  currentStep: Step,
  exeType: ExeType,
  fn: () -> T
): Either<Throwable, T> {
  logger.info { "$currentStep Executing $exeType" }
  return runCatching(fn)
    .fold(
      { Right(it) },
      {
        logger.error(it) { "‼️☠️ $currentStep Exception while executing $exeType" }
        Left(it)
      },
    )
}

internal fun isConsideredFailure(
  currentStepReport: StepReport,
  kick: Kick,
  stepReports: List<StepReport>
): Boolean =
  when {
    currentStepReport.isSuccessful -> true
    kick.haltOnAnyFailure() -> {
      logger.info {
        "${currentStepReport.step} failed, halting execution as `haltOnAnyFailure` is set to `true`"
      }
      true
    }
    else -> {
      kick
        .haltOnAnyFailureExcept()
        ?.pick(
          currentStepReport,
          Rundown(
            stepReports + currentStepReport,
            pm.environment,
            kick.haltOnAnyFailureExcept(),
          ),
        )
        ?.also {
          logger.info {
            if (it) {
              "${currentStepReport.step} failed, but ignoring failure as it qualifies `haltOnAnyFailureExcept`"
            } else {
              "${currentStepReport.step} failed and didn't qualify for `haltOnAnyFailureExcept`, so halting the execution"
            }
          }
        } ?: false
    }
  }

private val logger = KotlinLogging.logger {}
