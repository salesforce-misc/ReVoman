/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.UNMARSHALL_RESPONSE
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.failure.ResponseFailure.PostResJSFailure
import com.salesforce.revoman.output.report.failure.ResponseFailure.UnmarshallResponseFailure
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class ResponseFailureTest {
  private val moshiReVoman = initMoshi()

  private fun requestInfo(): TxnInfo<org.http4k.core.Request> =
    TxnInfo(
      txnObjType = String::class.java,
      txnObj = "req",
      httpMsg =
        Request(method = POST.toString(), url = Url("https://x.test/a"))
          .toHttpRequest(moshiReVoman),
      moshiReVoman = moshiReVoman,
    )

  private fun responseInfo(): TxnInfo<Response> =
    TxnInfo(
      txnObjType = String::class.java,
      txnObj = "res",
      httpMsg = Response(OK),
      moshiReVoman = moshiReVoman,
    )

  @Test
  fun `each ResponseFailure subtype reports its exeType`() {
    val boom = RuntimeException("boom")
    assertThat(PostResJSFailure(boom, requestInfo(), responseInfo()).exeType).isEqualTo(POST_RES_JS)
    assertThat(UnmarshallResponseFailure(boom, requestInfo(), responseInfo()).exeType)
      .isEqualTo(UNMARSHALL_RESPONSE)
  }

  @Test
  fun `ResponseFailure exposes failure, requestInfo and responseInfo`() {
    val boom = RuntimeException("boom")
    val req = requestInfo()
    val res = responseInfo()
    val failure = UnmarshallResponseFailure(boom, req, res)
    assertThat(failure.failure).isSameInstanceAs(boom)
    assertThat(failure.requestInfo).isSameInstanceAs(req)
    assertThat(failure.responseInfo).isSameInstanceAs(res)
  }
}
