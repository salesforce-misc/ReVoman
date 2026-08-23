/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionResult
import com.salesforce.revoman.internal.postman.sandbox.ScriptTarget
import com.salesforce.revoman.output.Rundown

internal fun interface KickExecutionFactory {
  /**
   * Creates a fully initialized but resource-lazy child transactionally. Ownership transfers only
   * after this method returns; any closeable opened before return remains the factory's rollback
   * responsibility.
   */
  @JvmSynthetic
  fun create(
    configuredKick: Kick,
    effectiveDynamicEnvironment: Map<String, Any?>,
  ): KickExecution
}

internal interface KickExecution : AutoCloseable {
  @JvmSynthetic fun execute(): Rundown

  @JvmSynthetic override fun close()
}

@JvmSynthetic
internal fun kickExecution(
  configuredKick: Kick,
  effectiveDynamicEnvironment: Map<String, Any?>,
  body: (Kick, Map<String, Any?>, ScriptExecutor) -> Rundown,
  sandboxFactory: SandboxFactory,
): KickExecution {
  return object : KickExecution {
    private var closed = false
    private var executed = false
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
          val runtime = sandbox ?: sandboxFactory.create().also { sandbox = it }
          return runtime.execute(script, target, context, timeoutMs)
        }
      }

    override fun execute(): Rundown {
      check(!closed) { "KickExecution is already closed" }
      check(!executed) { "KickExecution has already executed" }
      executed = true
      return body(configuredKick, effectiveDynamicEnvironment, executor)
    }

    override fun close() {
      if (closed) return
      closed = true
      try {
        sandbox?.close()
      } finally {
        sandbox = null
      }
    }
  }
}
