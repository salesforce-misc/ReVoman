/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.report.ExeType
import com.salesforce.revoman.output.report.TxInfo
import org.http4k.core.Request

sealed class HookFailure : ExeFailure() {
  abstract override val failure: Throwable

  data class PreHookFailure(override val failure: Throwable, val requestInfo: TxInfo<Request>) :
    HookFailure() {
    override val exeType = ExeType.PRE_HOOK
  }

  data class PostHookFailure(override val failure: Throwable) : HookFailure() {
    override val exeType = ExeType.POST_HOOK
  }
}
