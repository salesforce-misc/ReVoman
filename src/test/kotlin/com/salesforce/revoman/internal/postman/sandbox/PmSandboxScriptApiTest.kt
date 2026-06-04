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
