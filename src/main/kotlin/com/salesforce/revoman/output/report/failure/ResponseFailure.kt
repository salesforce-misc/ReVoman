/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.ExeType.TESTS_JS
import com.salesforce.revoman.output.ExeType.UNMARSHALL_RESPONSE
import com.salesforce.revoman.output.report.TxnInfo
import org.http4k.core.Request
import org.http4k.core.Response

sealed class ResponseFailure : ExeFailure() {
  abstract val requestInfo: TxnInfo<Request>
  abstract val responseInfo: TxnInfo<Response>

  data class TestsJSFailure(
    override val failure: Throwable,
    override val requestInfo: TxnInfo<Request>,
    override val responseInfo: TxnInfo<Response>,
  ) : ResponseFailure() {
    override val exeType = TESTS_JS
  }

  data class UnmarshallResponseFailure(
    override val failure: Throwable,
    override val requestInfo: TxnInfo<Request>,
    override val responseInfo: TxnInfo<Response>
  ) : ResponseFailure() {
    override val exeType = UNMARSHALL_RESPONSE
  }
}
