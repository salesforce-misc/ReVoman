/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A2 guard: the shared immutable GraalVM Engine reuses parsed bootcode across runs, but each run
 * gets its OWN Context. These tests pin that (a) repeated evals in one sandbox are deterministic
 * and (b) two sandboxes NEVER see each other's guest globals or env — the #1 state-bleed risk.
 */
class SandboxEngineSharingTest {
  private fun testCtx(env: Map<String, Any?> = emptyMap()) =
    PmExecutionContext(environment = PmScope("e", env))

  @Test
  fun `same script evaluated twice in one sandbox yields identical results`() {
    val sandbox = PmSandbox()
    val script =
      """
      pm.test('sum', () => pm.expect(1 + 1).to.eql(2));
      pm.environment.set('n', 41 + 1);
      """
        .trimIndent()
    val r1 = sandbox.execute(script, ScriptTarget.TEST, testCtx())
    val r2 = sandbox.execute(script, ScriptTarget.TEST, testCtx())
    sandbox.close()
    r1.error shouldBe null
    r2.error shouldBe null
    r1.assertions[0].passed shouldBe true
    r2.assertions[0].passed shouldBe true
    r1.environment["n"] shouldBe 42
    r2.environment["n"] shouldBe 42
  }

  @Test
  fun `two sandboxes sharing the Engine do not leak guest globals or env`() {
    val s1 = PmSandbox()
    // Leak a guest global via a bare undeclared assignment (non-strict global write). Inside the
    // postman-sandbox user-script scope `globalThis` is NOT a bound identifier — referencing it
    // throws `ReferenceError: globalThis is not defined` — so the leak is written/read through
    // the bare name, which is exactly the persistent-across-runs global the state-bleed guard
    // must catch.
    s1.execute("__leak = 'from-s1';", ScriptTarget.TEST, testCtx())

    // POSITIVE CONTROL: the bare-name global genuinely persists across execute() calls WITHIN one
    // sandbox (same Context). This pins the leak vector INSIDE the test — so if a future
    // postman-sandbox bump ever reset guest globals per exec (strict mode / fresh scope), THIS
    // assertion fails loudly instead of the cross-sandbox negative below silently passing on a
    // dead channel and giving the shared-Engine refactor a false safety net.
    val sameSandbox =
      s1.execute(
        "pm.environment.set('sawGlobalSameSandbox', typeof __leak);",
        ScriptTarget.TEST,
        testCtx(),
      )
    sameSandbox.error shouldBe null
    sameSandbox.environment["sawGlobalSameSandbox"] shouldBe "string" // persists within one sandbox

    // NEGATIVE: a DIFFERENT sandbox (its own Context) must NOT see s1's guest global.
    val s2 = PmSandbox()
    val r =
      s2.execute(
        "pm.environment.set('sawGlobal', typeof __leak);",
        ScriptTarget.TEST,
        testCtx(),
      )
    s1.close()
    s2.close()
    r.error shouldBe null
    r.environment["sawGlobal"] shouldBe "undefined" // fresh Context: s1's global unseen
  }
}
