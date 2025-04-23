/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.UNMARSHALL_RESPONSE
import com.salesforce.revoman.output.report.TxnInfo
import com.squareup.moshi.JsonClass
import dev.zacsweers.moshix.sealed.annotations.TypeLabel
import org.http4k.core.Request
import org.http4k.core.Response

@JsonClass(generateAdapter = true, generator = "sealed:type")
sealed class ResponseFailure : ExeFailure() {
  abstract val requestInfo: TxnInfo<Request>
  abstract val responseInfo: TxnInfo<Response>

  @TypeLabel("post-res-js")
  data class PostResJSFailure(
    override val failure: Throwable,
    override val requestInfo: TxnInfo<Request>,
    override val responseInfo: TxnInfo<Response>,
  ) : ResponseFailure() {
    override val exeType = POST_RES_JS
  }

  @TypeLabel("unmarshall-response")
  data class UnmarshallResponseFailure(
    override val failure: Throwable,
    override val requestInfo: TxnInfo<Request>,
    override val responseInfo: TxnInfo<Response>,
  ) : ResponseFailure() {
    override val exeType = UNMARSHALL_RESPONSE
  }
}
