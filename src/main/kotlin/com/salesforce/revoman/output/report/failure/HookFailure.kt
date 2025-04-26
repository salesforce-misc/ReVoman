/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.ExeType.POST_STEP_HOOK
import com.salesforce.revoman.output.ExeType.PRE_STEP_HOOK
import com.salesforce.revoman.output.report.TxnInfo
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import dev.zacsweers.moshix.sealed.annotations.TypeLabel
import org.http4k.core.Request
import org.http4k.core.Response

@JsonClass(generateAdapter = true, generator = "sealed:type")
sealed class HookFailure : ExeFailure() {
  @Json(ignore = true) abstract val requestInfo: TxnInfo<Request>

  @TypeLabel("pre-step-hook")
  data class PreStepHookFailure(
    override val failure: Throwable,
    @Json(ignore = true) override val requestInfo: TxnInfo<Request>,
  ) : HookFailure() {
    override val exeType = PRE_STEP_HOOK
  }

  @TypeLabel("post-step-hook")
  data class PostStepHookFailure(
    override val failure: Throwable,
    @Json(ignore = true) override val requestInfo: TxnInfo<Request>,
    @Json(ignore = true) @JvmField val responseInfo: TxnInfo<Response>,
  ) : HookFailure() {
    override val exeType = POST_STEP_HOOK
  }
}
