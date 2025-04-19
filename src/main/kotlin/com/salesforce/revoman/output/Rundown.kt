/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.output.Rundown.Stats.Unsuccessful
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Folder.Companion.FOLDER_DELIMITER
import com.salesforce.revoman.output.report.StepReport
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

data class Rundown(
  @JvmField val stepReports: List<StepReport> = emptyList(),
  @JvmField val mutableEnv: PostmanEnvironment<Any?>,
  private val haltOnFailureOfTypeExcept: Map<ExeType, PostTxnStepPick?>,
  private val providedStepsToExecuteCount: Int,
  private val moshiReVoman: MoshiReVoman,
) {
  @get:JvmName("immutableEnv") val immutableEnv: Map<String, Any?> by lazy { mutableEnv.toMap() }

  @get:JvmName("stats")
  val stats: Stats by lazy {
    Stats(
      providedStepsToExecuteCount,
      stepReports.size,
      Unsuccessful(
        stepReports.count { !it.isSuccessful },
        stepReports.count { !it.isHttpStatusSuccessful },
        stepReports.count { it.failure?.isLeft ?: false },
      ),
      moshiReVoman,
    )
  }

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
    stepReports.all { it.isSuccessful || !isStepIgnoredForFailure(it, this) }
  }

  fun reportsForStepsInFolder(folderName: String): List<StepReport?> =
    stepReports.filter { it.step.name.contains("$folderName$FOLDER_DELIMITER") }

  fun areAllStepsInFolderSuccessful(folderName: String): Boolean =
    reportsForStepsInFolder(folderName).all { it?.isSuccessful == true }

  fun reportForStepName(stepName: String): StepReport? =
    stepReports.firstOrNull { it.step.stepNameMatches(stepName) }

  fun filterReportExcludingStepsWithName(stepNames: Set<String>): List<StepReport> =
    stepReports.filter { r -> !stepNames.any { r.step.stepNameMatches(it) } }

  fun filterReportIncludingStepsWithName(stepNames: Set<String>): List<StepReport> =
    stepReports.filter { r -> stepNames.any { r.step.stepNameMatches(it) } }

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

  @JsonClass(generateAdapter = true)
  data class Stats(
    val providedStepsToExecuteCount: Int,
    val executedStepCount: Int,
    val unsuccessful: Unsuccessful,
    @Json(ignore = true) private val moshiReVoman: MoshiReVoman = initMoshi(),
  ) {
    @JsonClass(generateAdapter = true)
    data class Unsuccessful(
      val total: Int,
      val httpFailureStepCount: Int,
      val executionFailureStepCount: Int,
    )

    fun toJson() = moshiReVoman.toPrettyJson(this)
  }
}

fun <T> List<T>.endsWith(list: List<T>): Boolean =
  list.isNotEmpty() && list.size < size && subList(lastIndex - list.lastIndex, size) == list
