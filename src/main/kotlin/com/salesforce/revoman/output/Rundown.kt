/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.output

import com.salesforce.revoman.internal.toArrow
import com.salesforce.revoman.internal.toVavr
import com.salesforce.revoman.output.Rundown.StepReport.Failure.PostHookFailure
import com.salesforce.revoman.output.Rundown.StepReport.Failure.PreHookFailure
import com.salesforce.revoman.output.postman.PostmanEnvironment
import io.vavr.control.Either
import java.lang.reflect.Type
import org.http4k.core.Request
import org.http4k.core.Response

data class Rundown(
  @JvmField val stepNameToReport: Map<String, StepReport> = emptyMap(),
  @JvmField val mutableEnv: PostmanEnvironment<Any?> = PostmanEnvironment()
) {
  val immutableEnvMap
    @JvmName("immutableEnvMap") get() = mutableEnv.toMap()

  val firstUnsuccessfulStepNameInOrder: String?
    get() =
      stepNameToReport.entries.firstOrNull { (_, stepReport) -> !stepReport.isSuccessful }?.key

  val areAllStepsSuccessful
    @JvmName("areAllStepsSuccessful") get() = stepNameToReport.values.all { it.isSuccessful }

  fun reportsForStepsInFolder(folderName: String): List<StepReport?> =
    stepNameToReport.filter { it.key.contains("$folderName$FOLDER_DELIMITER") }.map { it.value }

  fun areAllStepsInFolderSuccessful(folderName: String): Boolean =
    reportsForStepsInFolder(folderName).all { it?.isSuccessful ?: false }

  fun reportForStepName(stepName: String): StepReport? =
    stepNameToReport.entries
      .firstOrNull { it.key == stepName || it.key.substringAfterLast(FOLDER_DELIMITER) == stepName }
      ?.value

  // ! TODO 20/09/23 gopala.akshintala: Enhance report viewing by overriding Either `toString()`
  data class StepReport
  private constructor(
    val status: String,
    val requestInfo: Either<out Failure, TxInfo<Request>>? = null,
    val preHookFailure: PreHookFailure? = null,
    val responseInfo: Either<out Failure, TxInfo<Response>>? = null,
    val postHookFailure: PostHookFailure? = null,
    val postmanEnvironmentSnapshot: PostmanEnvironment<Any?>
  ) {
    constructor(
      requestInfo: arrow.core.Either<Failure, TxInfo<Request>>? = null,
      preHookFailure: PreHookFailure? = null,
      responseInfo: arrow.core.Either<Failure, TxInfo<Response>>? = null,
      postHookFailure: PostHookFailure? = null,
      postmanEnvironmentSnapshot: PostmanEnvironment<Any?>
    ) : this(
      if (isSuccessful(requestInfo, preHookFailure, responseInfo, postHookFailure)) "✅" else "❌",
      requestInfo?.toVavr(),
      preHookFailure,
      responseInfo?.toVavr(),
      postHookFailure,
      postmanEnvironmentSnapshot
    )

    val isSuccessful: Boolean
      get() =
        isSuccessful(
          requestInfo?.toArrow(),
          preHookFailure,
          responseInfo?.toArrow(),
          postHookFailure
        )

    companion object {
      fun isSuccessful(
        requestInfo: arrow.core.Either<Failure, TxInfo<Request>>? = null,
        preHookFailure: PreHookFailure? = null,
        responseInfo: arrow.core.Either<Failure, TxInfo<Response>>? = null,
        postHookFailure: PostHookFailure? = null,
      ): Boolean =
        requestInfo?.isRight()
          ?: false &&
          preHookFailure == null &&
          responseInfo?.fold({ false }, { it.httpMsg.status.successful }) ?: false &&
          postHookFailure == null
    }

    data class TxInfo<HttpMsgT>(
      val txObjType: Type? = null,
      val txObj: Any? = null,
      val httpMsg: HttpMsgT
    ) {
      fun <T> getTypedTxObj(): T? = txObjType?.let { (it as Class<T>).cast(txObj) }
    }

    sealed class Failure private constructor(open val exeType: ExeType, open val failure: Any) {
      private constructor(failure: Failure) : this(failure.exeType, failure.failure)

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

      internal data class UnknownFailure(
        override val exeType: ExeType,
        override val failure: Throwable
      ) : Failure(exeType, failure)

      data class UnmarshallRequestFailure(
        val unmarshallRequestFailure: Failure,
        val requestInfo: TxInfo<Request>
      ) : Failure(unmarshallRequestFailure)

      data class PreHookFailure(val preHookFailure: Failure, val requestInfo: TxInfo<Request>) :
        Failure(preHookFailure)

      data class HttpRequestFailure(
        val httpRequestFailure: Failure,
        val requestInfo: TxInfo<Request>
      ) : Failure(httpRequestFailure)

      data class TestScriptJsFailure(
        val testScriptJsFailure: Failure,
        val requestInfo: TxInfo<Request>,
        val responseInfo: TxInfo<Response>
      ) : Failure(testScriptJsFailure)

      data class UnmarshallResponseFailure(
        val unmarshallResponseFailure: Failure,
        val requestInfo: TxInfo<Request>,
        val responseInfo: TxInfo<Response>
      ) : Failure(unmarshallResponseFailure)

      data class ValidationFailure(override val exeType: ExeType, override val failure: Any) :
        Failure(exeType, failure)

      data class ResponseValidationFailure(
        val validationFailure: Failure,
        val requestInfo: TxInfo<Request>,
        val responseInfo: TxInfo<Response>
      ) : Failure(validationFailure)

      data class PostHookFailure(val postHookFailure: Failure) : Failure(postHookFailure)
    }
  }
}

const val FOLDER_DELIMITER = "|>"
const val HTTP_METHOD_SEPARATOR = ": "
