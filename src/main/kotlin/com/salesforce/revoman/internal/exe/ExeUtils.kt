/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ****************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either
import com.salesforce.revoman.input.config.HookConfig.Hook
import com.salesforce.revoman.input.config.HookConfig.Hook.PostHook
import com.salesforce.revoman.input.config.HookConfig.Hook.PreHook
import com.salesforce.revoman.input.config.RequestConfig
import com.salesforce.revoman.input.config.ResponseConfig
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.salesforce.revoman.internal.postman.state.Auth
import com.salesforce.revoman.internal.postman.state.Item
import com.salesforce.revoman.output.FOLDER_DELIMITER
import com.salesforce.revoman.output.HTTP_METHOD_SEPARATOR
import com.salesforce.revoman.output.INDEX_SEPARATOR
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.Rundown.StepReport.TxInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.lang3.StringUtils
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.HttpMessage
import org.http4k.core.Request

internal fun isContentTypeApplicationJson(httpMessage: HttpMessage) =
  httpMessage.bodyString().isNotBlank() &&
    httpMessage.header("content-type")?.let {
      StringUtils.deleteWhitespace(it)
        .equals(StringUtils.deleteWhitespace(APPLICATION_JSON.toHeaderValue()), ignoreCase = true)
    } == true

internal fun List<Item>.deepFlattenItems(
  parentFolderName: String = "",
  parentIndex: String = "",
  authFromRoot: Auth? = null
): List<Item> =
  asSequence()
    .flatMapIndexed { itemIndex, item ->
      val concatWithParentFolder =
        if (parentFolderName.isBlank()) item.name
        else "$parentFolderName$FOLDER_DELIMITER${item.name}"
      val index = if (parentIndex.isBlank()) "${itemIndex + 1}" else "$parentIndex.${itemIndex + 1}"
      item.item
        ?.map { childItem -> childItem.copy(auth = childItem.auth ?: item.auth ?: authFromRoot) }
        ?.deepFlattenItems(concatWithParentFolder, index)
        ?: listOf(
          item.copy(
            name =
              "$index$INDEX_SEPARATOR${item.request.method}$HTTP_METHOD_SEPARATOR$concatWithParentFolder",
            auth = item.auth ?: authFromRoot
          )
        )
    }
    .toList()

internal inline fun <reified T : Hook> getHooksForStepName(
  currentStepName: String,
  stepNameToHooks: Map<String, List<Hook>>
): List<T> {
  val stepNameVariants = stepNameVariants(currentStepName)
  return stepNameToHooks
    .filterKeys { stepNameVariants.contains(it) }
    .values
    .flatten()
    .map { it as T }
}

internal fun pickPreHooks(
  preHooksWithPicks: List<Pair<PreTxnStepPick, PreHook>>,
  currentStepName: String,
  requestInfo: TxInfo<Request>,
  rundown: Rundown
): List<PreHook> =
  preHooksWithPicks
    .asSequence()
    .filter { it.first.pick(currentStepName, requestInfo, rundown) }
    .map { it.second }
    .toList()

internal fun pickPostHooks(
  postHooksWithPicks: List<Pair<PostTxnStepPick, PostHook>>,
  currentStepName: String,
  rundown: Rundown
): List<PostHook> =
  postHooksWithPicks
    .asSequence()
    .filter {
      it.first.pick(currentStepName, rundown.reportForStepName(currentStepName)!!, rundown)
    }
    .map { it.second }
    .toList()

internal fun stepNameVariants(fqStepName: String): Set<String> = buildSet {
  add(fqStepName)
  add(fqStepName.substringAfterLast(INDEX_SEPARATOR))
  add(fqStepNameToStepName(fqStepName))
}

internal fun fqStepNameToStepName(fqStepName: String): String =
  if (fqStepName.contains(FOLDER_DELIMITER)) {
    fqStepName.substringAfterLast(FOLDER_DELIMITER)
  } else {
    fqStepName.substringAfterLast(HTTP_METHOD_SEPARATOR)
  }

internal fun getRequestConfigForStepName(
  stepName: String,
  stepNameToRequestConfig: Map<String, RequestConfig>
): RequestConfig? = stepNameVariants(stepName).firstNotNullOfOrNull { stepNameToRequestConfig[it] }

internal fun pickRequestConfig(
  pickToRequestConfig: List<Pair<PreTxnStepPick, RequestConfig>>,
  currentStepName: String,
  currentRequestInfo: TxInfo<Request>,
  rundown: Rundown
): RequestConfig? =
  pickToRequestConfig
    .firstOrNull { it.first.pick(currentStepName, currentRequestInfo, rundown) }
    ?.second

internal fun pickResponseConfig(
  pickToResponseConfig: List<Pair<PostTxnStepPick, ResponseConfig>>,
  currentStepName: String,
  rundown: Rundown
): ResponseConfig? =
  pickToResponseConfig
    .firstOrNull {
      it.first.pick(currentStepName, rundown.reportForStepName(currentStepName)!!, rundown)
    }
    ?.second

internal fun getResponseConfigForStepName(
  stepName: String,
  httpStatus: Boolean,
  stepNameToResponseConfig: Map<Pair<Boolean, String>, ResponseConfig>
): ResponseConfig? =
  stepNameVariants(stepName).firstNotNullOfOrNull { stepNameToResponseConfig[httpStatus to it] }

internal fun isStepNameInPassList(
  currentStepName: String,
  haltOnAnyFailureExceptForSteps: Set<String>
) =
  haltOnAnyFailureExceptForSteps.isEmpty() ||
    haltOnAnyFailureExceptForSteps.contains(currentStepName) ||
    haltOnAnyFailureExceptForSteps.intersect(stepNameVariants(currentStepName)).isNotEmpty()

// ! TODO 24/06/23 gopala.akshintala: Regex support to filter Step Names
internal fun shouldStepBeExecuted(
  runOnlySteps: Set<String>,
  skipSteps: Set<String>,
  currentStepName: String
): Boolean {
  val stepNameVariants = stepNameVariants(currentStepName)
  return ((runOnlySteps.isEmpty() && skipSteps.isEmpty()) ||
    (runOnlySteps.isNotEmpty() && runOnlySteps.intersect(stepNameVariants).isNotEmpty()) ||
    (skipSteps.isNotEmpty() && skipSteps.intersect(stepNameVariants).isEmpty()))
}

internal fun <T> runChecked(
  stepName: String,
  exeType: Rundown.StepReport.ExeType,
  fn: () -> T
): Either<Throwable, T> =
  runCatching(fn)
    .fold(
      { Either.Right(it) },
      {
        logger.error(it) { "‼️ $stepName: Exception while executing $exeType" }
        Either.Left(it)
      }
    )

private val logger = KotlinLogging.logger {}
