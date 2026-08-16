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

internal fun interface KickBody {
  @JvmSynthetic fun execute(owner: KickExecution): Rundown
}

internal interface KickExecution : InternalCloseable {
  @get:JvmSynthetic val configuredKick: Kick

  @get:JvmSynthetic val effectiveDynamicEnvironment: Map<String, Any?>

  @get:JvmSynthetic val scripts: ScriptExecutor

  @get:JvmSynthetic val sandboxInitialized: Boolean

  @JvmSynthetic fun execute(): Rundown

  @JvmSynthetic override fun close()
}

@JvmSynthetic
internal fun kickExecution(
  configuredKick: Kick,
  effectiveDynamicEnvironment: Map<String, Any?>,
  body: KickBody,
  sandboxFactory: SandboxFactory,
): KickExecution {
  val scope = resourceScope()
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
          val runtime = sandbox ?: scope.own(sandboxFactory.create()).also { sandbox = it }
          return runtime.execute(script, target, context, timeoutMs)
        }
      }

    override val configuredKick: Kick = configuredKick

    override val effectiveDynamicEnvironment: Map<String, Any?> = effectiveDynamicEnvironment

    override val scripts: ScriptExecutor
      get() = executor

    override val sandboxInitialized: Boolean
      get() = sandbox != null

    override fun execute(): Rundown {
      check(!closed) { "KickExecution is already closed" }
      check(!executed) { "KickExecution has already executed" }
      executed = true
      return body.execute(this)
    }

    override fun close() {
      if (closed) return
      closed = true
      scope.close()
    }
  }
}
