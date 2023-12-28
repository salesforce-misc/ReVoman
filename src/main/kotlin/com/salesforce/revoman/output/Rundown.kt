/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ****************************************************************************
 */
package com.salesforce.revoman.output

import com.salesforce.revoman.internal.exe.isStepNameInPassList
import com.salesforce.revoman.internal.exe.stepNameVariants
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.StepReport

data class Rundown(
  @JvmField val stepNameToReport: List<StepReport> = emptyList(),
  @JvmField val mutableEnv: PostmanEnvironment<Any?> = PostmanEnvironment(),
  private val stepsToIgnoreForFailure: Set<String>
) {
  val immutableEnvMap = mutableEnv.toMap()

  val firstUnsuccessfulStepNameInOrder: String?
    @JvmName("firstUnsuccessfulStepNameInOrder")
    get() = stepNameToReport.firstOrNull { !it.isSuccessful }?.stepName

  fun firstUnIgnoredUnsuccessfulStepReportInOrder(): StepReport? =
    stepNameToReport.firstOrNull {
      !it.isSuccessful && !isStepNameInPassList(it.stepName, stepsToIgnoreForFailure)
    }

  val areAllStepsSuccessful
    @JvmName("areAllStepsSuccessful") get() = stepNameToReport.all { it.isSuccessful }

  val areAllStepsExceptIgnoredSuccessful
    @JvmName("areAllStepsExceptIgnoredSuccessful")
    get() =
      stepNameToReport.all {
        it.isSuccessful || isStepNameInPassList(it.stepName, stepsToIgnoreForFailure)
      }

  fun reportsForStepsInFolder(folderName: String): List<StepReport?> =
    stepNameToReport.filter { it.stepName.contains("$folderName$FOLDER_DELIMITER") }

  fun areAllStepsInFolderSuccessful(folderName: String): Boolean =
    reportsForStepsInFolder(folderName).all { it?.isSuccessful == true }

  fun reportForStepName(stepName: String): StepReport? {
    val stepNameVariants = stepNameVariants(stepName)
    return stepNameToReport.firstOrNull { stepNameVariants.contains(it.stepName) }
  }

  fun filterReportExcludingStepsWithName(stepNames: Set<String>): List<StepReport> {
    val stepNameVariantsToExclude = stepNames.flatMap { stepNameVariants(it) }
    return stepNameToReport.filter { !stepNameVariantsToExclude.contains(it.stepName) }
  }

  fun filterReportIncludingStepsWithName(stepNames: Set<String>): List<StepReport> {
    val stepNameVariantsToExclude = stepNames.flatMap { stepNameVariants(it) }
    return stepNameToReport.filter { stepNameVariantsToExclude.contains(it.stepName) }
  }
}

// ! TODO 12/10/23 gopala.akshintala: Come-up with a more sophisticated builders for steps
fun buildStepName(index: String, httpMethod: String, vararg path: String): String =
  "${index}${INDEX_SEPARATOR}${httpMethod}${HTTP_METHOD_SEPARATOR}" +
    path.joinToString(FOLDER_DELIMITER)

fun buildStepNameFromBasePath(basePath: String, vararg pathToAppend: String): String =
  basePath + pathToAppend.joinToString(FOLDER_DELIMITER)

const val FOLDER_DELIMITER = "|>"
const val HTTP_METHOD_SEPARATOR = " ~~> "
const val INDEX_SEPARATOR = " ### "
