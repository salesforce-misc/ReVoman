/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package org.revcloud.revoman.internal

import com.google.common.io.Resources
import java.util.function.Consumer
import mu.KotlinLogging
import org.apache.commons.lang3.StringUtils
import org.http4k.core.ContentType
import org.http4k.core.Response
import org.revcloud.revoman.input.HookConfig
import org.revcloud.revoman.input.HookType
import org.revcloud.revoman.internal.postman.state.Item
import org.revcloud.revoman.output.FOLDER_DELIMITER
import org.revcloud.revoman.output.Rundown

private val logger = KotlinLogging.logger {}

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
  hookConfigs: Set<HookConfig>,
  stepName: String,
  hookType: HookType
): List<Consumer<Rundown>> =
  hookConfigs
    .filter {
      (it.stepName == stepName || it.stepName == stepName.substringAfterLast(FOLDER_DELIMITER)) &&
        it.hookType == hookType
    }
    .map { it.hook }

internal fun isStepNameInPassList(stepName: String, haltOnAnyFailureExceptForSteps: Set<String>) =
  haltOnAnyFailureExceptForSteps.isEmpty() ||
    haltOnAnyFailureExceptForSteps.contains(stepName) ||
    haltOnAnyFailureExceptForSteps.contains(
      stepName.substringAfterLast(FOLDER_DELIMITER),
    )

internal fun <T> Map<String, T>.forStepName(stepName: String): T? =
  this[stepName] ?: this[stepName.substringAfterLast(FOLDER_DELIMITER)]

internal fun Map<String, Any>.isStepNamePresent(stepName: String): Boolean =
  containsKey(stepName) || containsKey(stepName.substringAfterLast(FOLDER_DELIMITER))

// ! TODO 24/06/23 gopala.akshintala: Regex support to filter Step Names
internal fun filterStep(runOnlySteps: Set<String>, skipSteps: Set<String>, stepName: String) =
  (runOnlySteps.isEmpty() && skipSteps.isEmpty()) ||
    (runOnlySteps.isNotEmpty() &&
      (runOnlySteps.contains(stepName) ||
        runOnlySteps.contains(stepName.substringAfterLast(FOLDER_DELIMITER))) ||
      (skipSteps.isNotEmpty() &&
        (!skipSteps.contains(stepName) &&
          !skipSteps.contains(stepName.substringAfterLast(FOLDER_DELIMITER)))))
