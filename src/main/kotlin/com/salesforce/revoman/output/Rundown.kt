/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.output

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
    get() = mutableEnv.toMap()

  val firstUnsuccessfulStepNameInOrder: String?
    get() =
      stepNameToReport.entries.firstOrNull { (_, stepReport) -> !stepReport.isSuccessful }?.key

  val areAllStepsSuccessful
    get() = stepNameToReport.values.all { it.isSuccessful }

  fun reportsForStepsInFolder(folderName: String): List<StepReport?> =
    stepNameToReport.filter { it.key.contains("$folderName$FOLDER_DELIMITER") }.map { it.value }

  fun areAllStepsInFolderSuccessful(folderName: String): Boolean =
    reportsForStepsInFolder(folderName).all { it?.isSuccessful ?: false }

  fun reportForStepName(stepName: String): StepReport? =
    stepNameToReport.entries
      .firstOrNull { it.key == stepName || it.key.substringAfterLast(FOLDER_DELIMITER) == stepName }
      ?.value

  data class StepReport(
    val requestInfo: Either<Failure, TxInfo<Request>>? = null,
    val preHookFailure: Failure? = null,
    val responseInfo: Either<Failure, TxInfo<Response>>? = null,
    val postHookFailure: Failure? = null,
    val postmanEnvironmentSnapshot: PostmanEnvironment<Any?>
  ) {
    val isSuccessful: Boolean
      get() =
        requestInfo?.isRight
          ?: false &&
          preHookFailure == null &&
          responseInfo?.isRight ?: false &&
          postHookFailure == null

    data class TxInfo<HttpMsgT>(
      val txObjType: Type? = null,
      val txObj: Any? = null,
      val httpMsg: HttpMsgT
    ) {
      fun <T> getTypedTxObj(): T? = txObjType?.let { (it as Class<T>).cast(txObj) }
    }

    data class Failure(val exeType: ExeType, val failure: Throwable) {
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
    }
  }
}

const val FOLDER_DELIMITER = "|>"
