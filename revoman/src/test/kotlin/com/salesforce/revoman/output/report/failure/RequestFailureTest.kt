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
import com.salesforce.revoman.output.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.ExeType.UNMARSHALL_REQUEST
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import com.salesforce.revoman.output.report.failure.RequestFailure.PreReqJSFailure
import com.salesforce.revoman.output.report.failure.RequestFailure.UnmarshallRequestFailure
import org.http4k.core.Method.POST
import org.junit.jupiter.api.Test

class RequestFailureTest {
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

  @Test
  fun `each RequestFailure subtype reports its exeType`() {
    val boom = RuntimeException("boom")
    assertThat(PreReqJSFailure(boom, requestInfo()).exeType).isEqualTo(PRE_REQ_JS)
    assertThat(UnmarshallRequestFailure(boom, requestInfo()).exeType).isEqualTo(UNMARSHALL_REQUEST)
    assertThat(HttpRequestFailure(boom, requestInfo()).exeType).isEqualTo(HTTP_REQUEST)
  }

  @Test
  fun `RequestFailure data-class equality holds for equal fields`() {
    val boom = RuntimeException("boom")
    val info = requestInfo()
    assertThat(HttpRequestFailure(boom, info)).isEqualTo(HttpRequestFailure(boom, info))
  }

  @Test
  fun `RequestFailure exposes its failure and requestInfo`() {
    val boom = RuntimeException("boom")
    val info = requestInfo()
    val failure = PreReqJSFailure(boom, info)
    assertThat(failure.failure).isSameInstanceAs(boom)
    assertThat(failure.requestInfo).isSameInstanceAs(info)
  }
}
