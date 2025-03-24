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
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.ExeStepPick
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.ExeType
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.Rundown.Companion.isStepIgnoredForFailure
import com.salesforce.revoman.output.report.Folder
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import io.github.oshai.kotlinlogging.KotlinLogging

internal fun deepFlattenItems(
  items: List<Item>,
  parentFolder: Folder? = null,
  stepIndexFromParent: String = "",
): List<Step> =
  items
    .asSequence()
    .flatMapIndexed { itemIndex, item ->
      val stepIndex =
        if (stepIndexFromParent.isBlank()) "${itemIndex + 1}"
        else "$stepIndexFromParent.${itemIndex + 1}"
      item.item
        ?.map { childItem ->
          childItem.copy(
            request = childItem.request.copy(auth = childItem.request.auth ?: item.request.auth)
          )
        }
        ?.let {
          val currentFolder = Folder(item.name, parentFolder)
          parentFolder?.subFolders?.add(currentFolder)
          deepFlattenItems(it, currentFolder, stepIndex)
        } ?: listOf(Step(stepIndex, item, parentFolder))
    }
    .toList()

internal fun shouldStepBePicked(
  currentStep: Step,
  runOnlySteps: List<ExeStepPick>,
  skipSteps: List<ExeStepPick>,
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

internal fun <T> runCatching(
  currentStep: Step,
  exeType: ExeType,
  fn: () -> T,
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

// ! TODO 01 Mar 2025 gopala.akshintala: Unit test this
internal fun shouldHaltExecution(
  currentStepReport: StepReport,
  kick: Kick,
  rundown: Rundown,
): Boolean =
  when {
    currentStepReport.isSuccessful -> false
    else -> {
      logger.info { "${currentStepReport.step} failed with ${currentStepReport.failure}" }
      when {
        kick.haltOnAnyFailure() -> {
          logger.info {
            "🛑 Halting the execution of next steps, as `haltOnAnyFailure` is set to true"
          }
          true
        }
        kick.haltOnFailureOfTypeExcept().isEmpty() -> {
          logger.info {
            "🛝 Continuing the execution of next steps, as `haltOnAnyFailure=${kick.haltOnAnyFailure()}` and `haltOnFailureOfTypeExcept` is empty"
          }
          false
        }
        else ->
          (!isStepIgnoredForFailure(currentStepReport, rundown)).also {
            logger.info {
              if (it) {
                "${currentStepReport.step} doesn't qualify `haltOnFailureOfTypeExcept` for `exeTypeForFailure=${currentStepReport.exeTypeForFailure}`, so 🛑 halting the execution of next steps"
              } else {
                "🛝 Continuing the execution of next steps, as the step is ignored for `exeTypeForFailure=${currentStepReport.exeTypeForFailure}`"
              }
            }
          }
      }
    }
  }

private val logger = KotlinLogging.logger {}
