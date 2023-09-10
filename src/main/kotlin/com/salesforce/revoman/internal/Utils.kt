/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.internal

import com.google.common.io.Resources
import com.salesforce.revoman.input.HookConfig
import com.salesforce.revoman.input.HookType
import com.salesforce.revoman.input.ResponseConfig
import com.salesforce.revoman.internal.postman.state.Item
import com.salesforce.revoman.output.FOLDER_DELIMITER
import com.salesforce.revoman.output.Rundown
import io.vavr.CheckedConsumer
import org.apache.commons.lang3.StringUtils
import org.http4k.core.ContentType
import org.http4k.core.Response

internal fun isContentTypeApplicationJson(response: Response) =
  response.bodyString().isNotBlank() &&
    response.header("content-type")?.let {
      StringUtils.deleteWhitespace(it)
        .equals(
          StringUtils.deleteWhitespace(ContentType.APPLICATION_JSON.toHeaderValue()),
          ignoreCase = true
        )
    }
      ?: false

internal fun readFileToString(fileRelativePath: String): String =
  Resources.getResource(fileRelativePath).readText()

internal fun List<Item>.deepFlattenItems(parentFolderName: String = ""): List<Item> =
  asSequence()
    .flatMap { item ->
      val concatWithParentFolder =
        if (parentFolderName.isEmpty()) item.name else "$parentFolderName|>${item.name}"
      item.item?.deepFlattenItems(concatWithParentFolder)
        ?: listOf(item.copy(name = "${item.request.method}: $concatWithParentFolder"))
    }
    .toList()

internal fun getHooksForStep(
  hookConfigs: Set<Set<HookConfig>>,
  currentStepName: String,
  hookType: HookType
): List<CheckedConsumer<Rundown>> =
  hookConfigs
    .flatten()
    .filter { stepNameEquals(it.stepName, currentStepName) && it.hookType == hookType }
    .map { it.hook }

private fun stepNameEquals(stepNameFromConfig: String, currentStepName: String) =
  stepNameFromConfig == currentStepName ||
    stepNameFromConfig == currentStepName.substringAfterLast(FOLDER_DELIMITER)

internal fun getResponseConfigForStepName(
  stepName: String,
  responseConfigs: Set<Set<ResponseConfig>>
): ResponseConfig? = responseConfigs.flatten().firstOrNull { stepNameEquals(it.stepName, stepName) }

internal fun isStepNameInPassList(stepName: String, haltOnAnyFailureExceptForSteps: Set<String>) =
  haltOnAnyFailureExceptForSteps.isEmpty() ||
    haltOnAnyFailureExceptForSteps.contains(stepName) ||
    haltOnAnyFailureExceptForSteps.contains(stepName.substringAfterLast(FOLDER_DELIMITER))

// ! TODO 24/06/23 gopala.akshintala: Regex support to filter Step Names
internal fun filterStep(runOnlySteps: Set<String>, skipSteps: Set<String>, stepName: String) =
  (runOnlySteps.isEmpty() && skipSteps.isEmpty()) ||
    (runOnlySteps.isNotEmpty() &&
      (runOnlySteps.contains(stepName) ||
        runOnlySteps.contains(stepName.substringAfterLast(FOLDER_DELIMITER))) ||
      (skipSteps.isNotEmpty() &&
        (!skipSteps.contains(stepName) &&
          !skipSteps.contains(stepName.substringAfterLast(FOLDER_DELIMITER)))))
