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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PmSandboxScriptApiTest {
  private val sandbox = PmSandbox()

  @AfterAll fun tearDown() = sandbox.close()

  private fun runTest(script: String, env: Map<String, Any?> = emptyMap()) =
    sandbox.execute(script, ScriptTarget.TEST, PmExecutionContext(environment = PmScope("e", env)))

  @Test
  fun `pm test and expect chai assertions`() {
    val r = runTest("pm.test('t', () => pm.expect(2).to.be.a('number'));")
    r.assertions shouldHaveSize 1
    r.assertions[0].passed shouldBe true
  }

  @Test
  fun `pm environment get set unset`() {
    val r =
      runTest(
        """
        pm.environment.set('a', '1');
        pm.environment.unset('seed');
        pm.test('has a', () => pm.expect(pm.environment.get('a')).to.eql('1'));
        """
          .trimIndent(),
        env = mapOf("seed" to "x"),
      )
    r.assertions[0].passed shouldBe true
    r.environment["a"] shouldBe "1"
    (r.environment.containsKey("seed")) shouldBe false
  }

  @Test
  fun `integral numbers round-trip as Int not Double`() {
    // JSON has no int/double distinction; the bridge must narrow integral doubles back to Int so
    // consumer code (getInt, equality vs Int literals) sees the same type the old in-JS path gave.
    val r = runTest("pm.environment.set('n', 1);")
    r.environment["n"] shouldBe 1
  }

  @Test
  fun `untouched integral env value keeps its Int type`() {
    // A pre-existing integral env value the script never touches must come back as the same Int,
    // not a Double — otherwise the env-sync diff would spuriously flag it as produced and corrupt
    // the stored type (regression guard for the Pokemon limit=1 -> 1.0 bug).
    val r =
      runTest("pm.test('noop', () => pm.expect(true).to.eql(true));", env = mapOf("limit" to 1))
    r.environment["limit"] shouldBe 1
  }

  @Test
  fun `pm collectionVariables get set unset round-trips`() {
    val r =
      sandbox.execute(
        """
        pm.test('reads seeded collectionVariable', () =>
          pm.expect(pm.collectionVariables.get('seed')).to.eql('seedVal'));
        pm.collectionVariables.set('produced', 'p1');
        pm.collectionVariables.unset('toRemove');
        pm.test('reads back set collectionVariable', () =>
          pm.expect(pm.collectionVariables.get('produced')).to.eql('p1'));
        """
          .trimIndent(),
        ScriptTarget.TEST,
        PmExecutionContext(
          environment = PmScope("e", emptyMap()),
          collectionVariables = PmScope("cv", mapOf("seed" to "seedVal", "toRemove" to "x")),
        ),
      )
    r.error shouldBe null
    r.assertions[0].passed shouldBe true // get() reads the seeded value
    r.assertions[1].passed shouldBe true // set() is visible within the same script
    r.collectionVariables["produced"] shouldBe "p1" // set() round-trips back to the host
    (r.collectionVariables.containsKey("toRemove")) shouldBe false // unset() round-trips back
  }

  @Test
  fun `console log runs but is not captured in the execution result`() {
    val r =
      runTest(
        """
        console.log('hello from pm', { a: 1 });
        console.warn('a warning');
        console.error('an error');
        console.info('some info');
        pm.test('script completed after console calls', () => pm.expect(true).to.be.true);
        """
          .trimIndent()
      )
    // console.* exist in the sandbox (bootcode provides them) so calling them does NOT throw.
    r.error shouldBe null
    r.assertions[0].passed shouldBe true
    // PmExecutionResult has NO console/log field: the guest dispatches `execution.console` events
    // but SandboxBridge.decodeResult has no branch for them and boot installs no capture — so the
    // logged lines are silently dropped, never surfaced to the host or printed by ReVoman.
  }

  @Test
  fun `pm response json and status assertions`() {
    val r =
      sandbox.execute(
        "pm.test('ok', () => { pm.response.to.have.status(200); pm.expect(pm.response.json().x).to.eql(7); });",
        ScriptTarget.TEST,
        PmExecutionContext(
          environment = PmScope("e", emptyMap()),
          response = mapOf("code" to 200, "status" to "OK", "body" to "{\"x\":7}"),
        ),
      )
    r.assertions[0].passed shouldBe true
  }

  @Test
  fun `dynamic variable guid via replaceIn`() {
    val r =
      runTest(
        """
        const id = pm.variables.replaceIn('{{${'$'}guid}}');
        pm.test('guid shape', () => pm.expect(id).to.match(/^[0-9a-f-]{36}${'$'}/));
        """
          .trimIndent()
      )
    r.assertions[0].passed shouldBe true
  }

  @Test
  fun `failing assertion is data not exception`() {
    val r = runTest("pm.test('fails', () => pm.expect(1).to.eql(2));")
    r.error shouldBe null
    r.assertions[0].passed shouldBe false
  }

  @Test
  fun `thrown error surfaces as result error`() {
    val r = runTest("throw new Error('boom');")
    (r.error?.message?.contains("boom")) shouldBe true
  }
}
