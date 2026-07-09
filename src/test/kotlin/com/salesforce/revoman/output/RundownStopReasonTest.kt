/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import arrow.core.Either.Right
import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.failure.HookFailure.PostStepHookFailure
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class RundownStopReasonTest {
  private val moshiReVoman = initMoshi()

  @Test
  fun `stopReason defaults to COMPLETED`() {
    val rundown =
      Rundown(
        stepReports = emptyList(),
        mutableEnv = PostmanEnvironment(),
        haltOnFailureOfTypeExcept = emptyMap(),
        providedStepsToExecuteCount = 0,
      )
    assertThat(rundown.stopReason).isEqualTo(StopReason.COMPLETED)
  }

  @Test
  fun `areAllStepsExceptIgnoredSuccessful when only ignored failures exist`() {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val responseInfo: TxnInfo<Response> =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeResponse",
        httpMsg = Response(OK),
        moshiReVoman = moshiReVoman,
      )
    val failedStepReport =
      StepReport(
        Step("", Item(request = rawRequest)),
        Right(requestInfo),
        null,
        Right(responseInfo),
        PostStepHookFailure(RuntimeException("fakeRTE"), requestInfo, responseInfo),
        null,
        null,
        pmEnvSnapshot = PostmanEnvironment(),
      )
    val rundown =
      Rundown(
        stepReports = listOf(failedStepReport),
        mutableEnv = PostmanEnvironment(),
        haltOnFailureOfTypeExcept =
          mapOf(ExeType.POST_STEP_HOOK to PostTxnStepPick { _, _ -> true }),
        providedStepsToExecuteCount = 0,
      )
    assertThat(rundown.areAllStepsExceptIgnoredSuccessful).isTrue()
  }
}
