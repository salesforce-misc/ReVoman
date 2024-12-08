/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.ExeType.UNMARSHALL_REQUEST
import com.salesforce.revoman.output.report.TxnInfo
import org.http4k.core.Request

sealed class RequestFailure : ExeFailure() {
  abstract val requestInfo: TxnInfo<Request>

  data class PreReqJSFailure(
    override val failure: Throwable,
    override val requestInfo: TxnInfo<Request>,
  ) : RequestFailure() {
    override val exeType = PRE_REQ_JS
  }

  data class UnmarshallRequestFailure(
    override val failure: Throwable,
    override val requestInfo: TxnInfo<Request>
  ) : RequestFailure() {
    override val exeType = UNMARSHALL_REQUEST
  }

  data class HttpRequestFailure(
    override val failure: Throwable,
    override val requestInfo: TxnInfo<Request>
  ) : RequestFailure() {
    override val exeType = HTTP_REQUEST
  }
}
