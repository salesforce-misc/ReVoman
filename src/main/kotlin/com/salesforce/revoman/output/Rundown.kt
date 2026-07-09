/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.output.ledger.LedgerEntry
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Folder.Companion.FOLDER_DELIMITER
import com.salesforce.revoman.output.report.StepReport

data class Rundown(
  @JvmField val stepReports: List<StepReport> = emptyList(),
  @JvmField val mutableEnv: PostmanEnvironment<Any?>,
  private val haltOnFailureOfTypeExcept: Map<ExeType, PostTxnStepPick?>,
  @JvmField val providedStepsToExecuteCount: Int,
  @JvmField val learnedLedger: Map<String, LedgerEntry> = emptyMap(),
  /**
   * The `pm.collectionVariables` scope after the run — a peer of [mutableEnv] (which is the
   * `pm.environment` scope). Script-seeded only; defaulted empty so existing constructions and
   * callers are unaffected.
   */
  @JvmField val collectionVariables: PostmanEnvironment<Any?> = PostmanEnvironment(),
  /**
   * The `pm.globals` scope after the run — a peer of [mutableEnv]. Script-seeded only; defaulted
   * empty so existing constructions and callers are unaffected.
   */
  @JvmField val globals: PostmanEnvironment<Any?> = PostmanEnvironment(),
  @JvmField val stopReason: StopReason = StopReason.COMPLETED,
) {
  @get:JvmName("immutableEnv") val immutableEnv: Map<String, Any?> by lazy { mutableEnv.toMap() }

  @get:JvmName("executedStepCount") val executedStepCount: Int by lazy { stepReports.size }

  @get:JvmName("httpFailureStepCount")
  val httpFailureStepCount: Int by lazy { stepReports.count { !it.isHttpStatusSuccessful } }

  @get:JvmName("unsuccessfulStepCount")
  val unsuccessfulStepCount: Int by lazy { stepReports.count { !it.isSuccessful } }

  @get:JvmName("executionFailureStepCount")
  val executionFailureStepCount: Int by lazy { stepReports.count { it.failure?.isLeft ?: false } }

  @get:JvmName("firstUnsuccessfulStepReport")
  val firstUnsuccessfulStepReport: StepReport? by lazy {
    stepReports.firstOrNull { !it.isSuccessful }
  }

  @get:JvmName("firstUnIgnoredUnsuccessfulStepReport")
  val firstUnIgnoredUnsuccessfulStepReport: StepReport? by lazy {
    stepReports.firstOrNull { stepReport ->
      !stepReport.isSuccessful && !isStepIgnoredForFailure(stepReport, this)
    }
  }

  @get:JvmName("areAllStepsSuccessful")
  val areAllStepsSuccessful: Boolean by lazy { stepReports.all { it.isSuccessful } }

  @get:JvmName("areAllStepsExceptIgnoredSuccessful")
  val areAllStepsExceptIgnoredSuccessful: Boolean by lazy {
    stepReports.all { it.isSuccessful || isStepIgnoredForFailure(it, this) }
  }

  fun reportsForStepsInFolder(folderName: String): List<StepReport?> = stepReports.filter {
    it.step.name.contains("$folderName$FOLDER_DELIMITER")
  }

  fun areAllStepsInFolderSuccessful(folderName: String): Boolean =
    reportsForStepsInFolder(folderName).all { it?.isSuccessful == true }

  /**
   * The report for the named step. When a step ran multiple times (a control-flow loop), returns
   * the LAST (most-recent) execution. Use [reportsForStepName] for the full per-iteration history.
   */
  fun reportForStepName(stepName: String): StepReport? = stepReports.lastOrNull {
    it.step.stepNameMatches(stepName)
  }

  /** All reports for the named step, in execution order (one per iteration for a looped step). */
  fun reportsForStepName(stepName: String): List<StepReport> = stepReports.filter {
    it.step.stepNameMatches(stepName)
  }

  fun filterReportExcludingStepsWithName(stepNames: Set<String>): List<StepReport> =
    stepReports.filter { r ->
      !stepNames.any { r.step.stepNameMatches(it) }
    }

  fun filterReportIncludingStepsWithName(stepNames: Set<String>): List<StepReport> =
    stepReports.filter { r ->
      stepNames.any { r.step.stepNameMatches(it) }
    }

  companion object {
    fun isStepIgnoredForFailure(stepReport: StepReport, rundown: Rundown): Boolean =
      rundown.haltOnFailureOfTypeExcept
        .asSequence()
        .map { (exeType, postTxnPick) ->
          stepReport.exeTypeForFailure == exeType &&
            (postTxnPick?.pick(stepReport, rundown) ?: false)
        }
        .any { it }
  }
}

fun <T> List<T>.endsWith(list: List<T>): Boolean =
  list.isNotEmpty() && list.size <= size && subList(lastIndex - list.lastIndex, size) == list
