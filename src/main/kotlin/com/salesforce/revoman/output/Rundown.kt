/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.output

import com.salesforce.revoman.internal.isStepNameInPassList
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.internal.stepNameVariants
import com.salesforce.revoman.internal.toVavr
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.POST_HOOK
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.PRE_HOOK
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.RESPONSE_VALIDATION
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.TEST_SCRIPT_JS
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.UNMARSHALL_REQUEST
import com.salesforce.revoman.output.Rundown.StepReport.ExeType.UNMARSHALL_RESPONSE
import com.salesforce.revoman.output.Rundown.StepReport.HookFailure.PostHookFailure
import com.salesforce.revoman.output.Rundown.StepReport.HookFailure.PreHookFailure
import com.salesforce.revoman.output.postman.PostmanEnvironment
import io.vavr.control.Either
import io.vavr.control.Either.Left
import io.vavr.control.Either.Right
import io.vavr.control.Either.left
import io.vavr.control.Either.right
import java.lang.reflect.Type
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.core.Response

data class Rundown(
  @JvmField val stepNameToReport: Map<String, StepReport> = emptyMap(),
  @JvmField val mutableEnv: PostmanEnvironment<Any?> = PostmanEnvironment(),
  private val stepsToIgnoreForFailure: Set<String>,
) {
  val immutableEnvMap
    @JvmName("immutableEnvMap") get() = mutableEnv.toMap()

  val firstUnsuccessfulStepNameInOrder: String?
    @JvmName("firstUnsuccessfulStepNameInOrder")
    get() =
      stepNameToReport.entries.firstOrNull { (_, stepReport) -> !stepReport.isSuccessful }?.key

  val firstUnIgnoredUnsuccessfulStepNameInOrder: String?
    @JvmName("firstUnIgnoredUnsuccessfulStepNameInOrder")
    get() =
      stepNameToReport.entries
        .firstOrNull { (stepName, stepReport) ->
          !stepReport.isSuccessful && !isStepNameInPassList(stepName, stepsToIgnoreForFailure)
        }
        ?.key

  val areAllStepsSuccessful
    @JvmName("areAllStepsSuccessful") get() = stepNameToReport.values.all { it.isSuccessful }

  val areAllStepsExceptIgnoredSuccessful
    @JvmName("areAllStepsExceptIgnoredSuccessful")
    get() =
      stepNameToReport.all { (stepName, stepReport) ->
        stepReport.isSuccessful || isStepNameInPassList(stepName, stepsToIgnoreForFailure)
      }

  fun reportsForStepsInFolder(folderName: String): List<StepReport?> =
    stepNameToReport.filter { it.key.contains("$folderName$FOLDER_DELIMITER") }.map { it.value }

  fun areAllStepsInFolderSuccessful(folderName: String): Boolean =
    reportsForStepsInFolder(folderName).all { it?.isSuccessful ?: false }

  fun reportForStepName(stepName: String): StepReport? {
    val stepNameVariants = stepNameVariants(stepName)
    return stepNameToReport.entries.firstOrNull { stepNameVariants.contains(stepName) }?.value
  }

  fun filterReportExcludingStepsWithName(stepNames: Set<String>): Map<String, StepReport> {
    val stepNameVariantsToExclude = stepNames.flatMap { stepNameVariants(it) }
    return stepNameToReport.filterKeys { !stepNameVariantsToExclude.contains(it) }
  }

  fun filterReportIncludingStepsWithName(stepNames: Set<String>): Map<String, StepReport> {
    val stepNameVariantsToExclude = stepNames.flatMap { stepNameVariants(it) }
    return stepNameToReport.filterKeys { stepNameVariantsToExclude.contains(it) }
  }

  // ! TODO 20/09/23 gopala.akshintala: Enhance report viewing by overriding Either `toString()`
  data class StepReport
  private constructor(
    val requestInfo: Either<out RequestFailure, TxInfo<Request>>? = null,
    val preHookFailure: PreHookFailure? = null,
    val responseInfo: Either<out ResponseFailure, TxInfo<Response>>? = null,
    val postHookFailure: PostHookFailure? = null,
    val envSnapshot: PostmanEnvironment<Any?>
  ) {
    internal constructor(
      requestInfo: arrow.core.Either<RequestFailure, TxInfo<Request>>? = null,
      preHookFailure: PreHookFailure? = null,
      responseInfo: arrow.core.Either<ResponseFailure, TxInfo<Response>>? = null,
      postHookFailure: PostHookFailure? = null,
    ) : this(
      requestInfo?.toVavr(),
      preHookFailure,
      responseInfo?.toVavr(),
      postHookFailure,
      pm.environment.copy()
    )

    val isSuccessful: Boolean
      get() = failure(requestInfo, preHookFailure, responseInfo, postHookFailure) == null

    val exeFailure: ExeFailure?
      get() =
        failure(requestInfo, preHookFailure, responseInfo, postHookFailure)?.fold({ it }, { null })

    val isHttpStatusSuccessful: Boolean
      get() =
        failure(requestInfo, preHookFailure, responseInfo, postHookFailure)
          ?.fold({ false }, { false })
          ?: true

    companion object {
      private fun failure(
        requestInfo: Either<out ExeFailure, TxInfo<Request>>? = null,
        preHookFailure: PreHookFailure? = null,
        responseInfo: Either<out ExeFailure, TxInfo<Response>>? = null,
        postHookFailure: PostHookFailure? = null,
      ): Either<ExeFailure, ExeType>? =
        when {
          requestInfo != null ->
            when (requestInfo) {
              is Left -> left(requestInfo.left)
              else ->
                when {
                  preHookFailure != null -> left(preHookFailure)
                  responseInfo != null ->
                    when (responseInfo) {
                      is Left -> left(responseInfo.left)
                      is Right ->
                        when {
                          !responseInfo.get().httpMsg.status.successful -> right(HTTP_REQUEST)
                          else ->
                            when {
                              postHookFailure != null -> left(postHookFailure)
                              else -> null
                            }
                        }
                      else -> null
                    }
                  else -> null
                }
            }
          else -> null
        }
    }

    data class TxInfo<HttpMsgT : HttpMessage>(
      val txObjType: Type? = null,
      val txObj: Any? = null,
      val httpMsg: HttpMsgT
    ) {
      fun <T> getTypedTxObj(): T? = txObjType?.let { (it as Class<T>).cast(txObj) }

      override fun toString(): String {
        val prefix =
          when (httpMsg) {
            is Request -> "RequestInfo⬆️"
            is Response -> "ResponseInfo⬇️"
            else -> "TxInfo"
          }
        return "$prefix(Type=$txObjType, Obj=$txObj, $httpMsg)"
      }
    }

    enum class ExeType(private val exeName: String) {
      UNMARSHALL_REQUEST("unmarshall-request"),
      PRE_HOOK("pre-hook"),
      HTTP_REQUEST("http-request"),
      TEST_SCRIPT_JS("testScript-js"),
      UNMARSHALL_RESPONSE("unmarshall-response"),
      RESPONSE_VALIDATION("response-validation"),
      POST_HOOK("post-hook");

      override fun toString(): String {
        return exeName
      }
    }

    sealed class ExeFailure {
      abstract val exeType: ExeType
      abstract val failure: Any
    }

    sealed class RequestFailure private constructor() : ExeFailure() {
      abstract override val failure: Throwable
      abstract val requestInfo: TxInfo<Request>

      data class UnmarshallRequestFailure(
        override val failure: Throwable,
        override val requestInfo: TxInfo<Request>
      ) : RequestFailure() {
        override val exeType = UNMARSHALL_REQUEST
      }

      data class HttpRequestFailure(
        override val failure: Throwable,
        override val requestInfo: TxInfo<Request>
      ) : RequestFailure() {
        override val exeType = HTTP_REQUEST
      }
    }

    sealed class HookFailure private constructor() : ExeFailure() {
      abstract override val failure: Throwable

      data class PreHookFailure(override val failure: Throwable, val requestInfo: TxInfo<Request>) :
        HookFailure() {
        override val exeType = PRE_HOOK
      }

      data class PostHookFailure(override val failure: Throwable) : HookFailure() {
        override val exeType = POST_HOOK
      }
    }

    sealed class ResponseFailure private constructor() : ExeFailure() {
      abstract override val failure: Throwable
      abstract val responseInfo: TxInfo<Response>

      data class TestScriptJsFailure(
        override val failure: Throwable,
        override val responseInfo: TxInfo<Response>
      ) : ResponseFailure() {
        override val exeType = TEST_SCRIPT_JS
      }

      data class UnmarshallResponseFailure(
        override val failure: Throwable,
        override val responseInfo: TxInfo<Response>
      ) : ResponseFailure() {
        override val exeType = UNMARSHALL_RESPONSE
      }

      data class ResponseValidationFailure(
        override val failure: Throwable,
        override val responseInfo: TxInfo<Response>
      ) : ResponseFailure() {
        override val exeType = RESPONSE_VALIDATION

        data class ValidationFailure(val failure: Any) : Throwable()
      }
    }

    override fun toString(): String =
      when {
        exeFailure != null -> "❌$exeFailure"
        !isHttpStatusSuccessful ->
          "⚠️️Unsuccessful HTTP Status: ${responseInfo?.get()?.httpMsg?.status} \n${requestInfo?.get()}, ${responseInfo?.get()}"
        else -> "✅${requestInfo?.get()}, ${responseInfo?.get()}"
      }
  }
}

// ! TODO 12/10/23 gopala.akshintala: Come-up with a more sophisticated builders for steps
fun buildStepName(index: String, httpMethod: String, vararg path: String): String =
  "${index}${INDEX_SEPARATOR}${httpMethod}${HTTP_METHOD_SEPARATOR}" +
    path.joinToString(FOLDER_DELIMITER)

fun buildStepNameFromBasePath(basePath: String, vararg pathToAppend: String): String =
  basePath + pathToAppend.joinToString(FOLDER_DELIMITER)

const val FOLDER_DELIMITER = "|>"
const val HTTP_METHOD_SEPARATOR = "~~> "
const val INDEX_SEPARATOR = " ### "
