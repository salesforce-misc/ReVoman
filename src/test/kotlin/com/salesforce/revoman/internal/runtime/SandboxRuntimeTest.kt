/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionResult
import com.salesforce.revoman.internal.postman.sandbox.PmScope
import com.salesforce.revoman.internal.postman.sandbox.ScriptTarget
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SandboxRuntimeTest {
  @Test
  fun `factory creates an owned executor with the sandbox default timeout`() {
    val expectedResult =
      PmExecutionResult(
        environment = mapOf("token" to "value"),
        globals = emptyMap(),
        collectionVariables = emptyMap(),
        assertions = emptyList(),
        error = null,
      )
    val runtime = RecordingSandboxRuntime(expectedResult)
    val factory = SandboxFactory { runtime }
    val ownedRuntime: SandboxRuntime = factory.create()
    val executor: ScriptExecutor = ownedRuntime
    val context = PmExecutionContext(environment = PmScope("environment", emptyMap()))

    val actualResult = executor.execute("pm.environment.get('token')", ScriptTarget.TEST, context)
    ownedRuntime.close()

    assertSame(expectedResult, actualResult)
    assertThat(runtime.invocation)
      .isEqualTo(
        Invocation(
          script = "pm.environment.get('token')",
          target = ScriptTarget.TEST,
          context = context,
          timeoutMs = 60_000L,
        )
      )
    assertThat(runtime.closeCount).isEqualTo(1)
  }

  private class RecordingSandboxRuntime(private val result: PmExecutionResult) : SandboxRuntime {
    var invocation: Invocation? = null
      private set

    var closeCount: Int = 0
      private set

    override fun execute(
      script: String,
      target: ScriptTarget,
      context: PmExecutionContext,
      timeoutMs: Long,
    ): PmExecutionResult {
      invocation = Invocation(script, target, context, timeoutMs)
      return result
    }

    override fun close() {
      closeCount++
    }
  }

  private data class Invocation(
    val script: String,
    val target: ScriptTarget,
    val context: PmExecutionContext,
    val timeoutMs: Long,
  )
}
