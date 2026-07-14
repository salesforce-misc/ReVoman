/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either.Right
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxnInfo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class UnmarshallResponseBodyTest {

  private val moshiReVoman = initMoshi()

  private fun reportFor(response: Response): StepReport {
    val rawRequest = Request(method = POST.toString(), url = Url("https://test.example.com/"))
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    val responseInfo = TxnInfo(httpMsg = response, moshiReVoman = moshiReVoman)
    return StepReport(
      Step("1", Item(request = rawRequest)),
      Right(requestInfo),
      responseInfo = Right(responseInfo),
      pmEnvSnapshot = PostmanEnvironment(),
    )
  }

  private fun emptyRundown(): Rundown =
    Rundown(
      stepReports = emptyList(),
      mutableEnv = PostmanEnvironment(),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 0,
    )

  @Test
  fun `JSON body unmarshals to a Map`() {
    val kick = Kick.configure().off()
    val response = Response(OK).header("Content-Type", "application/json").body("""{"a":1}""")
    val result = unmarshallResponse(kick, moshiReVoman, reportFor(response), emptyRundown())
    result.shouldBeInstanceOf<Right<TxnInfo<Response>>>()
    val txnObj = result.value.txnObj
    txnObj.shouldBeInstanceOf<Map<*, *>>()
    txnObj.containsKey("a") shouldBe true
  }

  @Test
  fun `blank body yields isJson false`() {
    val kick = Kick.configure().off()
    val response = Response(OK).header("Content-Type", "application/json").body("")
    val result = unmarshallResponse(kick, moshiReVoman, reportFor(response), emptyRundown())
    result.shouldBeInstanceOf<Right<TxnInfo<Response>>>()
    result.value.isJson shouldBe false
  }

  @Test
  fun `non-JSON content-type yields isJson false`() {
    val kick = Kick.configure().off()
    val response = Response(OK).header("Content-Type", "text/plain").body("hello")
    val result = unmarshallResponse(kick, moshiReVoman, reportFor(response), emptyRundown())
    result.shouldBeInstanceOf<Right<TxnInfo<Response>>>()
    result.value.isJson shouldBe false
  }
}
