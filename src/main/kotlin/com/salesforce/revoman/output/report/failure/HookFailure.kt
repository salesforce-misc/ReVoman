/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report.failure

import com.salesforce.revoman.output.ExeType.POST_HOOK
import com.salesforce.revoman.output.ExeType.PRE_HOOK
import com.salesforce.revoman.output.report.TxnInfo
import org.http4k.core.Request

sealed class HookFailure : ExeFailure() {

  data class PreHookFailure(override val failure: Throwable, val requestInfo: TxnInfo<Request>) :
    HookFailure() {
    override val exeType = PRE_HOOK
  }

  data class PostHookFailure(override val failure: Throwable) : HookFailure() {
    override val exeType = POST_HOOK
  }
}
