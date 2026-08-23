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
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PmSandboxBootTest {
  @Test
  fun `boot does not execute a synthetic script before the caller's first execution`() {
    val bridge = SandboxBridge()

    try {
      bridge.boot()

      val emittedEventNames =
        bridge.emittedPayloads().mapNotNull { payload ->
          (Flatted.parse(payload) as? List<*>)?.firstOrNull() as? String
        }
      emittedEventNames.none { it.startsWith("execution.") } shouldBe true
    } finally {
      bridge.close()
    }
  }

  @Test
  fun `boot does not traverse postman collection through the global module loader`() {
    lateinit var context: Context
    lateinit var bridge: SandboxBridge
    var postmanCollectionRequireCalls = 0
    bridge =
      SandboxBridge()
        .withBootHooks(
          afterContextCreated = {
            context = bridgeContext(bridge)
            context
              .getBindings("js")
              .putMember(
                "__observePostmanCollectionRequire",
                ProxyExecutable {
                  postmanCollectionRequireCalls++
                  null
                },
              )
            context.eval(
              "js",
              """
              const observePostmanCollectionRequire = __observePostmanCollectionRequire;
              let browserRequire;
              Object.defineProperty(globalThis, 'require', {
                configurable: true,
                get: function () { return browserRequire; },
                set: function (value) {
                  browserRequire = function (moduleName) {
                    if (moduleName === 'postman-collection') {
                      observePostmanCollectionRequire();
                    }
                    return value.apply(this, arguments);
                  };
                }
              });
              """
                .trimIndent(),
            )
          },
          closeContext = { it.close(true) },
        )

    try {
      bridge.boot()

      postmanCollectionRequireCalls shouldBe 0
    } finally {
      bridge.close()
    }
  }

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
  // Preserve the boot-failure, replacement, and post-failure lifecycle sequence in one scenario.
  @Suppress("LongMethod")
  fun `PmSandbox makes a failed boot terminal and preserves cleanup failure ordering`() {
    val failure = IllegalStateException("after-context")
    val closeFailure = IllegalStateException("close-context")
    var failedBridgeBootCount = 0
    var failedBridgeCloseCount = 0
    var replacementBridgeBootCount = 0
    var replacementBridgeCloseCount = 0
    val replacementBridge =
      SandboxBridge()
        .withBootHooks(
          afterContextCreated = { replacementBridgeBootCount++ },
          closeContext = {
            replacementBridgeCloseCount++
            it.close(true)
          },
        )
    val sandbox =
      PmSandbox()
        .withBridgeForTest(
          SandboxBridge()
            .withBootHooks(
              afterContextCreated = {
                failedBridgeBootCount++
                throw failure
              },
              closeContext = {
                failedBridgeCloseCount++
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
    val replacementFailure =
      assertThrows<IllegalStateException> { sandbox.withBridgeForTest(replacementBridge) }
    val laterExecuteFailure =
      assertThrows<IllegalStateException> {
        sandbox.execute(
          "pm.test('must not dispatch', () => pm.expect(false).to.eql(true));",
          ScriptTarget.TEST,
          PmExecutionContext(environment = PmScope("e", emptyMap())),
          5000,
        )
      }
    sandbox.close()
    sandbox.close()

    thrown shouldBe failure
    thrown.suppressed.toList() shouldBe listOf(closeFailure)
    replacementFailure.message shouldBe "sandbox: bridge replacement after use"
    laterExecuteFailure.message shouldBe "sandbox: execute() after close()"
    failedBridgeBootCount shouldBe 1
    failedBridgeCloseCount shouldBe 1
    replacementBridgeBootCount shouldBe 0
    replacementBridgeCloseCount shouldBe 0
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

  @Suppress("UNCHECKED_CAST")
  private fun SandboxBridge.emittedPayloads(): List<String> {
    val field = SandboxBridge::class.java.getDeclaredField("emits")
    field.isAccessible = true
    return field.get(this) as List<String>
  }

  private fun bridgeContext(bridge: SandboxBridge): Context {
    val field = SandboxBridge::class.java.getDeclaredField("ctx")
    field.isAccessible = true
    return field.get(bridge) as Context
  }
}
