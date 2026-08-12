/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionResult
import com.salesforce.revoman.internal.postman.sandbox.ScriptTarget
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReVomanRuntimeTest {
  @Test
  fun `single kick runtime opens and closes exactly one session`() {
    var opens = 0
    var closes = 0
    var creates = 0
    val expected = rundown(mutableMapOf("result" to "ok"))
    val childFactory = KickExecutionFactory { kick, environment ->
      creates++
      fakeExecution(kick, environment, expected)
    }
    val runtime =
      reVomanRuntime(
        ExecutionSessionFactory { initial ->
          opens++
          val delegate = executionSession(initial, childFactory)
          object : ExecutionSession by delegate {
            override fun close() {
              closes++
              delegate.close()
            }
          }
        }
      )

    val actual = runtime.execute(Kick.configure().off())

    assertThat(actual).isSameInstanceAs(expected)
    assertThat(opens).isEqualTo(1)
    assertThat(creates).isEqualTo(1)
    assertThat(closes).isEqualTo(1)
  }

  @Test
  fun `runtime closes its session when child body fails`() {
    val failure = IllegalStateException("body")
    var closes = 0
    val runtime =
      reVomanRuntime(
        ExecutionSessionFactory { initial ->
          val delegate =
            executionSession(
              initial,
              KickExecutionFactory { kick, environment ->
                fakeExecution(kick, environment, rundown(mutableMapOf()), failure)
              },
            )
          object : ExecutionSession by delegate {
            override fun close() {
              closes++
              delegate.close()
            }
          }
        }
      )

    val thrown =
      assertThrows<IllegalStateException> {
        runtime.execute(Kick.configure().off())
      }

    assertThat(thrown).isSameInstanceAs(failure)
    assertThat(closes).isEqualTo(1)
  }

  @Test
  fun `real no-script path never creates sandbox runtime`() {
    val sandboxes = CountingSandboxFactory()

    val rundown = reVomanRuntime(sandboxes).execute(Kick.configure().off())

    assertThat(rundown.stepReports).isEmpty()
    assertThat(sandboxes.createCount).isEqualTo(0)
    assertThat(sandboxes.closeCount).isEqualTo(0)
  }

  @Test
  fun `real script path creates and closes exactly one sandbox runtime`() {
    val sandboxes = CountingSandboxFactory()
    val kick =
      Kick.configure()
        .templatePath("pm-templates/v3/cf-skip")
        .dynamicEnvironment("baseUrl", "http://127.0.0.1:1")
        .off()

    reVomanRuntime(sandboxes).execute(kick)

    assertThat(sandboxes.createCount).isEqualTo(1)
    assertThat(sandboxes.closeCount).isEqualTo(1)
    assertThat(sandboxes.executeCount).isEqualTo(1)
  }

  private fun fakeExecution(
    kick: Kick,
    environment: Map<String, Any?>,
    rundown: Rundown,
    failure: Throwable? = null,
  ): KickExecution =
    object : KickExecution {
      override val configuredKick: Kick = kick
      override val effectiveDynamicEnvironment: Map<String, Any?> = environment
      override val scripts: ScriptExecutor
        get() = error("scripts are not used")

      override val sandboxInitialized: Boolean = false

      override fun execute(): Rundown {
        failure?.let { throw it }
        return rundown
      }

      override fun close() = Unit
    }

  private fun rundown(environment: MutableMap<String, Any?>): Rundown =
    Rundown(
      mutableEnv = PostmanEnvironment(environment),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 0,
    )

  private class CountingSandboxFactory : SandboxFactory {
    var createCount = 0
    var closeCount = 0
    var executeCount = 0

    override fun create(): SandboxRuntime {
      createCount++
      return object : SandboxRuntime {
        override fun execute(
          script: String,
          target: ScriptTarget,
          context: PmExecutionContext,
          timeoutMs: Long,
        ): PmExecutionResult {
          executeCount++
          return PmExecutionResult(
            environment = context.environment.values,
            globals = context.globals.values,
            collectionVariables = context.collectionVariables.values,
            assertions = emptyList(),
            error = null,
          )
        }

        override fun close() {
          closeCount++
        }
      }
    }
  }
}
