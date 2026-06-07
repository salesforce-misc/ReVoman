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
import com.salesforce.revoman.output.ledger.LedgerSnapshot
import com.salesforce.revoman.output.report.Folder
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import kotlin.time.measureTimedValue
import kotlin.time.toJavaDuration

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
        } ?: listOf(Step(stepIndex, item, parentFolder, item.sourceHash))
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
  val skipStep = skipSteps.isNotEmpty() && skipSteps.any { it.pick(currentStep) }
  // Ambiguity only exists when a step is *explicitly* picked by both lists. An empty
  // runOnlySteps means "run anything not skipped" — not a positive pick that conflicts
  // with skipSteps.
  val explicitlyRunStep = runOnlySteps.isNotEmpty() && runOnlySteps.any { it.pick(currentStep) }
  check(!(explicitlyRunStep && skipStep)) {
    "‼️😵‍💫 Ambiguous - $currentStep is picked for both run and skip execution"
  }
  if (skipStep) {
    logger.info { "$currentStep skipped for execution" }
    return false
  }
  return runOnlySteps.isEmpty() || explicitlyRunStep
}

/**
 * Ledger-skip predicate: skip a step's HTTP dispatch iff the ledger has an entry for it whose
 * produced keys are ALL already in [env] AND whose producer fingerprint matches the step's current
 * [Step.sourceHash]. Empty-produces entries are NEVER skippable (read-only steps must always run);
 * a hash mismatch falls through to run (warn-and-run, handled by the caller). An EMPTY hash on
 * either side is treated as "unknown" and never matches — a real v3-loaded step and a real ledgered
 * entry both carry a computed sha256, so an empty hash can only come from a non-v3 step or a
 * corrupt/hand-crafted ledger; in that case we must run, not skip on an incidental "" == "".
 *
 * A step opts OUT of skipping — NEVER skipped, even when a perfectly cacheable producer — via
 * either the per-step `x-revoman-ledger: off` header ([Step.optsOutOfLedger]) OR a central
 * Kick-level pick in [optOutSteps] (e.g. by URL pattern). Its response is the assertion target, so
 * it must always dispatch fresh. Without this, an act-step that incidentally
 * `pm.environment.set(...)`s a value (e.g. a booking call computing its own scheduled-time window)
 * would be treated as an idempotent producer and skipped on warm runs, handing the test a cached
 * null body to assert on.
 */
internal fun ledgerSkipDecision(
  step: Step,
  ledger: LedgerSnapshot,
  env: Set<String>,
  optOutSteps: List<ExeStepPick> = emptyList(),
): Boolean {
  if (step.optsOutOfLedger || optOutSteps.any { it.pick(step) }) return false
  val entry = ledger.steps[step.path] ?: return false
  if (entry.produces.isEmpty()) return false
  if (entry.hash.isEmpty() || step.sourceHash.isEmpty()) return false
  if (entry.hash != step.sourceHash) return false
  return env.containsAll(entry.produces)
}

/**
 * Collision guard: the set of step paths that must NOT be ledger-skipped because they are an
 * EARLIER producer of a key that a LATER step also produces. Skipping such a step would inject the
 * LATER (final) value in place of the value the earlier step actually wrote — and any step BETWEEN
 * them that consumed the earlier value would then read the wrong value (silently wrong, no loud
 * failure). So only the LAST producer of a re-set key is safely skippable; every earlier producer
 * of it always runs.
 *
 * Pure over the run's picked steps in EXECUTION ORDER ([orderedSteps]) and the [ledger]: a step is
 * shadowed iff ANY key in its ledgered `produces` is also produced by a step appearing later in
 * [orderedSteps]. Steps with no ledger entry are ignored (they run anyway). Collision-free
 * collections (every key produced by exactly one step) yield an empty set — zero behavior change.
 */
internal fun shadowedProducerPaths(orderedSteps: List<Step>, ledger: LedgerSnapshot): Set<String> {
  // Last execution index at which each key is produced (by any ledgered step).
  val lastProducerIndexByKey = mutableMapOf<String, Int>()
  orderedSteps.forEachIndexed { index, step ->
    ledger.steps[step.path]?.produces?.forEach { key -> lastProducerIndexByKey[key] = index }
  }
  return orderedSteps
    .asSequence()
    .withIndex()
    .filter { (index, step) ->
      ledger.steps[step.path]?.produces?.any { key -> lastProducerIndexByKey[key]!! > index }
        ?: false
    }
    .map { (_, step) -> step.path }
    .toSet()
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

internal inline fun <T> timed(
  currentStep: Step,
  exeTimings: MutableMap<ExeType, Duration>,
  exeType: ExeType,
  block: () -> T,
): T {
  val (result, elapsed) = measureTimedValue(block)
  exeTimings[exeType] = elapsed.toJavaDuration()
  logger.info { "$currentStep $exeType completed in $elapsed" }
  return result
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
