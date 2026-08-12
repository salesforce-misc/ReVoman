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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KickExecutionTest {
  private val context = PmExecutionContext(environment = PmScope("environment", emptyMap()))
  private val result =
    PmExecutionResult(
      environment = emptyMap(),
      globals = emptyMap(),
      collectionVariables = emptyMap(),
      assertions = emptyList(),
      error = null,
    )

  @Test
  fun `construction and reading scripts do not create a sandbox`() {
    val factory = CountingSandboxFactory(result)
    val execution = kickExecution(factory)

    val executor: ScriptExecutor = execution.scripts

    assertThat(executor).isNotNull()
    assertThat(factory.createCount).isEqualTo(0)
    assertThat(execution.sandboxInitialized).isEqualTo(false)
  }

  @Test
  fun `first script invocation creates one sandbox reused by later phases`() {
    val factory = CountingSandboxFactory(result)
    val execution = kickExecution(factory)

    execution.scripts.execute("pre", ScriptTarget.PRE_REQUEST, context)
    execution.scripts.execute("test", ScriptTarget.TEST, context)

    assertThat(factory.createCount).isEqualTo(1)
    assertThat(factory.created.single().invocations)
      .containsExactly(
        Invocation("pre", ScriptTarget.PRE_REQUEST, context, 60_000L),
        Invocation("test", ScriptTarget.TEST, context, 60_000L),
      )
      .inOrder()
    assertThat(execution.sandboxInitialized).isEqualTo(true)
  }

  @Test
  fun `separate kick executions own separate sandboxes`() {
    val factory = CountingSandboxFactory(result)
    val first = kickExecution(factory)
    val second = kickExecution(factory)

    first.scripts.execute("one", ScriptTarget.TEST, context)
    second.scripts.execute("two", ScriptTarget.TEST, context)

    assertThat(factory.createCount).isEqualTo(2)
    assertThat(factory.created[0]).isNotSameInstanceAs(factory.created[1])
  }

  @Test
  fun `closing before first script access does not create a sandbox`() {
    val factory = CountingSandboxFactory(result)
    val execution = kickExecution(factory)

    execution.close()

    assertThat(factory.createCount).isEqualTo(0)
    assertThat(execution.sandboxInitialized).isEqualTo(false)
  }

  @Test
  fun `closing after script access closes the sandbox once`() {
    val factory = CountingSandboxFactory(result)
    val execution = kickExecution(factory)
    execution.scripts.execute("test", ScriptTarget.TEST, context)

    execution.close()
    execution.close()

    assertThat(factory.created.single().closeCount).isEqualTo(1)
  }

  @Test
  fun `script invocation after close fails without creating a sandbox`() {
    val factory = CountingSandboxFactory(result)
    val execution = kickExecution(factory)
    val executor = execution.scripts
    execution.close()

    val failure =
      assertThrows<IllegalStateException> { executor.execute("test", ScriptTarget.TEST, context) }

    assertThat(failure).hasMessageThat().contains("closed")
    assertThat(factory.createCount).isEqualTo(0)
  }

  private class CountingSandboxFactory(private val result: PmExecutionResult) : SandboxFactory {
    var createCount: Int = 0
      private set

    val created = mutableListOf<RecordingSandboxRuntime>()

    override fun create(): SandboxRuntime =
      RecordingSandboxRuntime(result).also {
        createCount++
        created += it
      }
  }

  private class RecordingSandboxRuntime(private val result: PmExecutionResult) : SandboxRuntime {
    val invocations = mutableListOf<Invocation>()
    var closeCount: Int = 0
      private set

    override fun execute(
      script: String,
      target: ScriptTarget,
      context: PmExecutionContext,
      timeoutMs: Long,
    ): PmExecutionResult {
      invocations += Invocation(script, target, context, timeoutMs)
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
