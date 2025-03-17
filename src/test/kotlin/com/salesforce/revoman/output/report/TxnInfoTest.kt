/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.report.TxnInfo.Companion.uriPathContains
import com.salesforce.revoman.output.report.TxnInfo.Companion.uriPathEndsWith
import org.http4k.core.Method.POST
import org.junit.jupiter.api.Test

class TxnInfoTest {
  private val moshiReVoman = initMoshi()

  @Test
  fun uriPathContains() {
    val rawRequest =
      Request(
        method = POST.toString(),
        url = Url("https://overfullstack.github.io/posts/huh-to-aha/"),
      )
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    assertThat(requestInfo.uriPathContains("huh-to-aha")).isTrue()
    assertThat(requestInfo.uriPathContains("posts")).isTrue()
    assertThat(requestInfo.uriPathContains("posts/huh-to-aha/")).isTrue()
    assertThat(requestInfo.uriPathContains("posts/huh-to-aha/something")).isFalse()
  }

  @Test
  fun uriPathEndsWith() {
    val rawRequest =
      Request(
        method = POST.toString(),
        url = Url("https://overfullstack.github.io/posts/huh-to-aha/"),
      )
    val requestInfo =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeRequest",
        httpMsg = rawRequest.toHttpRequest(moshiReVoman),
        moshiReVoman = moshiReVoman,
      )
    assertThat(requestInfo.uriPathEndsWith("huh-to-aha")).isTrue()
    assertThat(requestInfo.uriPathEndsWith("posts")).isFalse()
    assertThat(requestInfo.uriPathEndsWith("posts/huh-to-aha/")).isTrue()
    assertThat(requestInfo.uriPathEndsWith("posts/huh-to-aha/something")).isFalse()
  }
}
