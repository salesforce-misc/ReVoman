/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.report.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.report.ExeType.UNMARSHALL_REQUEST
import com.salesforce.revoman.output.report.TxInfo
import org.http4k.core.Request

sealed class RequestFailure : ExeFailure() {
  abstract override val failure: Throwable
  abstract val requestInfo: TxInfo<Request>

  data class UnmarshallRequestFailure(
    override val failure: Throwable,
    override val requestInfo: TxInfo<Request>
  ) : RequestFailure() {
    override val exeType = UNMARSHALL_REQUEST
  }

  data class HttpRequestFailure(
    override val failure: Throwable,
    override val requestInfo: TxInfo<Request>
  ) : RequestFailure() {
    override val exeType = HTTP_REQUEST
  }
}
