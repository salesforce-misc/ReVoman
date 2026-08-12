/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PmSandboxBootTest {
  @Test
  fun `real pm API boots under GraalJS and runs pm test + environment set`() {
    val bridge = SandboxBridge()
    bridge.boot()
    val result =
      bridge.dispatchExecute(
        id = "boot1",
        script =
          """
          pm.environment.set('spikeKey', 'spikeVal-' + (1 + 1));
          pm.test('one plus one is two', function () { pm.expect(1 + 1).to.eql(2); });
          pm.test('env round-trips', function () {
            pm.expect(pm.environment.get('spikeKey')).to.eql('spikeVal-2');
          });
          pm.test('intentional failure', function () { pm.expect(true).to.eql(false); });
          """
            .trimIndent(),
        target = ScriptTarget.TEST,
        context = PmExecutionContext(environment = PmScope("env1", emptyMap())),
        timeoutMs = 5000,
      )
    bridge.close()

    result.error shouldBe null
    result.assertions shouldHaveSize 3
    result.assertions[0].passed shouldBe true
    result.assertions[1].passed shouldBe true
    result.assertions[2].passed shouldBe false
    result.environment["spikeKey"] shouldBe "spikeVal-2"
  }

  @Test
  fun `boot failure after context creation closes the context once`() {
    val failure = IllegalStateException("after-context")
    var closeCount = 0
    val bridge =
      SandboxBridge()
        .withBootHooks(
          afterContextCreated = { throw failure },
          closeContext = {
            closeCount++
            it.close(true)
          },
        )

    assertThrows<IllegalStateException> { bridge.boot() } shouldBe failure
    bridge.close()

    closeCount shouldBe 1
  }

  @Test
  fun `boot failure remains primary when context cleanup fails and later close is idempotent`() {
    val failure = IllegalStateException("after-context")
    val closeFailure = IllegalStateException("close-context")
    var closeCount = 0
    val bridge =
      SandboxBridge()
        .withBootHooks(
          afterContextCreated = { throw failure },
          closeContext = {
            closeCount++
            it.close(true)
            throw closeFailure
          },
        )

    val thrown = assertThrows<IllegalStateException> { bridge.boot() }
    bridge.close()

    thrown shouldBe failure
    thrown.suppressed.toList() shouldBe listOf(closeFailure)
    closeCount shouldBe 1
  }

  @Test
  fun `PmSandbox closes a failed bridge once and preserves boot failure with close suppression`() {
    val failure = IllegalStateException("after-context")
    val closeFailure = IllegalStateException("close-context")
    var closeCount = 0
    val sandbox =
      PmSandbox()
        .withBridgeForTest(
          SandboxBridge()
            .withBootHooks(
              afterContextCreated = { throw failure },
              closeContext = {
                closeCount++
                it.close(true)
                throw closeFailure
              },
            )
        )

    val thrown =
      assertThrows<IllegalStateException> {
        sandbox.execute(
          "test",
          ScriptTarget.TEST,
          PmExecutionContext(environment = PmScope("e", emptyMap())),
          5000,
        )
      }
    sandbox.close()
    sandbox.close()

    thrown shouldBe failure
    thrown.suppressed.toList() shouldBe listOf(closeFailure)
    closeCount shouldBe 1
  }

  @Test
  fun `PmSandbox rejects bridge replacement after close`() {
    val sandbox = PmSandbox()
    sandbox.close()

    assertThrows<IllegalStateException> { sandbox.withBridgeForTest(SandboxBridge()) }
  }

  @Test
  fun `PmSandbox rejects bridge replacement after boot`() {
    val sandbox = PmSandbox()
    sandbox.execute(
      "pm.test('booted', () => pm.expect(true).to.eql(true));",
      ScriptTarget.TEST,
      PmExecutionContext(environment = PmScope("e", emptyMap())),
      5000,
    )
    try {
      assertThrows<IllegalStateException> { sandbox.withBridgeForTest(SandboxBridge()) }
    } finally {
      sandbox.close()
    }
  }
}
