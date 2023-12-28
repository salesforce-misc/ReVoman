/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 *  Version 2.0 For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.report.ExeType
import com.salesforce.revoman.output.report.TxInfo
import org.http4k.core.Response

sealed class ResponseFailure : ExeFailure() {
  abstract override val failure: Throwable
  abstract val responseInfo: TxInfo<Response>

  data class TestScriptJsFailure(
    override val failure: Throwable,
    override val responseInfo: TxInfo<Response>
  ) : ResponseFailure() {
    override val exeType = ExeType.TEST_SCRIPT_JS
  }

  data class UnmarshallResponseFailure(
    override val failure: Throwable,
    override val responseInfo: TxInfo<Response>
  ) : ResponseFailure() {
    override val exeType = ExeType.UNMARSHALL_RESPONSE
  }

  data class ResponseValidationFailure(
    override val failure: Throwable,
    override val responseInfo: TxInfo<Response>
  ) : ResponseFailure() {
    override val exeType = ExeType.RESPONSE_VALIDATION

    data class ValidationFailure(val failure: Any) : Throwable()
  }
}
