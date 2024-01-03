/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: 
 *           Apache License Version 2.0 
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/
package com.salesforce.revoman.output

import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Folder.Companion.FOLDER_DELIMITER
import com.salesforce.revoman.output.report.StepReport

data class Rundown(
  @JvmField val stepReports: List<StepReport> = emptyList(),
  @JvmField val mutableEnv: PostmanEnvironment<Any?> = PostmanEnvironment(),
  private val stepsToIgnoreForFailurePick: PostTxnStepPick?
) {
  @JvmField val immutableEnvMap = mutableEnv.toMap()

  val firstUnsuccessfulStepNameInOrder: String?
    @JvmName("firstUnsuccessfulStepNameInOrder")
    get() = stepReports.firstOrNull { !it.isSuccessful }?.step?.name

  fun firstUnIgnoredUnsuccessfulStepReportInOrder(): StepReport? =
    stepReports.firstOrNull {
      !it.isSuccessful && !(stepsToIgnoreForFailurePick?.pick(it, this) ?: false)
    }

  val areAllStepsSuccessful
    @JvmName("areAllStepsSuccessful") get() = stepReports.all { it.isSuccessful }

  val areAllStepsExceptIgnoredSuccessful
    @JvmName("areAllStepsExceptIgnoredSuccessful")
    get() =
      stepReports.all { it.isSuccessful || (stepsToIgnoreForFailurePick?.pick(it, this) ?: false) }

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
}

fun <T> List<T>.endsWith(list: List<T>): Boolean =
  list.isNotEmpty() && list.size < size && subList(lastIndex - list.lastIndex, size) == list
