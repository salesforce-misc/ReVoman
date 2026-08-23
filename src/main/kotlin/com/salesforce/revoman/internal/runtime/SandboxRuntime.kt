/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

import com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionResult
import com.salesforce.revoman.internal.postman.sandbox.ScriptTarget

internal interface ScriptExecutor {
  @JvmSynthetic
  fun execute(
    script: String,
    target: ScriptTarget,
    context: PmExecutionContext,
    timeoutMs: Long = SANDBOX_DEFAULT_TIMEOUT_MS,
  ): PmExecutionResult
}

internal interface SandboxRuntime : ScriptExecutor, AutoCloseable

internal fun interface SandboxFactory {
  @JvmSynthetic fun create(): SandboxRuntime
}

private const val SANDBOX_DEFAULT_TIMEOUT_MS = 60_000L
