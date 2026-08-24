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
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.junit.jupiter.api.Test

class TxnInfoContainsHeaderTest {
  private val moshiReVoman = initMoshi()

  private fun txn(request: Request): TxnInfo<Request> =
    TxnInfo(httpMsg = request, moshiReVoman = moshiReVoman)

  @Test
  fun `containsHeader true when header present`() {
    val info = txn(Request(GET, "https://x.y/z").header("X-Trace", "abc"))
    assertThat(info.containsHeader("X-Trace")).isTrue()
  }

  @Test
  fun `containsHeader false when header absent`() {
    val info = txn(Request(GET, "https://x.y/z").header("X-Trace", "abc"))
    assertThat(info.containsHeader("X-Missing")).isFalse()
  }

  @Test
  fun `containsHeader is case-sensitive on the key (preserves toMap semantics)`() {
    val info = txn(Request(GET, "https://x.y/z").header("X-Trace", "abc"))
    assertThat(info.containsHeader("x-trace")).isFalse()
  }
}
