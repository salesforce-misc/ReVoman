/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.ExeType.HTTP_STATUS
import com.salesforce.revoman.output.report.TxnInfo
import com.squareup.moshi.JsonClass
import org.http4k.core.Request
import org.http4k.core.Response

@JsonClass(generateAdapter = true)
data class HttpStatusUnsuccessful(
  @JvmField val requestInfo: TxnInfo<Request>,
  @JvmField val responseInfo: TxnInfo<Response>,
) {
  @JvmField val exeType = HTTP_STATUS
}
