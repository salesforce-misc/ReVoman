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
import com.salesforce.revoman.internal.postman.sandbox.PmSandbox
import com.salesforce.revoman.internal.postman.sandbox.ScriptTarget

internal interface KickExecution : InternalCloseable {
  @get:JvmSynthetic val scripts: ScriptExecutor

  @get:JvmSynthetic val sandboxInitialized: Boolean

  @JvmSynthetic override fun close()
}

@JvmSynthetic
internal fun kickExecution(
  sandboxFactory: SandboxFactory = DEFAULT_SANDBOX_FACTORY
): KickExecution {
  val scope = resourceScope()
  return object : KickExecution {
    private var closed = false
    private var sandbox: SandboxRuntime? = null
    private val executor =
      object : ScriptExecutor {
        override fun execute(
          script: String,
          target: ScriptTarget,
          context: PmExecutionContext,
          timeoutMs: Long,
        ): PmExecutionResult {
          check(!closed) { "KickExecution is already closed" }
          val runtime = sandbox ?: scope.own(sandboxFactory.create()).also { sandbox = it }
          return runtime.execute(script, target, context, timeoutMs)
        }
      }

    override val scripts: ScriptExecutor
      get() = executor

    override val sandboxInitialized: Boolean
      get() = sandbox != null

    override fun close() {
      if (closed) return
      closed = true
      scope.close()
    }
  }
}

private val DEFAULT_SANDBOX_FACTORY = SandboxFactory { PmSandbox() }
