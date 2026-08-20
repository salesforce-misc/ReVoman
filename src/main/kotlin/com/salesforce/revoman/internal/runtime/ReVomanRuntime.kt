/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

import com.salesforce.revoman.input.PostExeHook
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.internal.exe.executeRunbookInSession
import com.salesforce.revoman.internal.postman.sandbox.PmSandbox
import com.salesforce.revoman.output.RunbookRundown
import com.salesforce.revoman.output.Rundown

internal class ReVomanRuntime(private val sessions: ExecutionSessionFactory) {
  @JvmSynthetic
  fun execute(kick: Kick): Rundown =
    sessions.open(emptyMap()).use { session ->
      session.executeKick(kick, carryForward = false)
    }

  @JvmSynthetic
  fun execute(
    kicks: List<Kick>,
    postExeHook: PostExeHook,
    dynamicEnvironment: Map<String, Any?>,
  ): List<Rundown> =
    sessions.open(dynamicEnvironment).use { session ->
      kicks.map { kick ->
        session.executeKick(
          configuredKick = kick,
          carryForward = true,
          beforeCarry = { current, accumulated ->
            postExeHook.accept(current, accumulated)
          },
        )
      }
    }

  @JvmSynthetic
  fun execute(
    runbook: Runbook,
    dynamicEnvironment: Map<String, Any?>,
  ): RunbookRundown =
    sessions.open(emptyMap()).use { session ->
      executeRunbookInSession(session, runbook, dynamicEnvironment)
    }
}

@JvmSynthetic
internal fun reVomanRuntime(sessions: ExecutionSessionFactory): ReVomanRuntime =
  ReVomanRuntime(sessions)

@JvmSynthetic
internal fun reVomanRuntime(sandboxFactory: SandboxFactory): ReVomanRuntime =
  reVomanRuntime(executionSessionFactory(kickExecutionFactory(sandboxFactory)))

@JvmSynthetic
internal fun reVomanRuntime(): ReVomanRuntime = reVomanRuntime(SandboxFactory(::PmSandbox))
