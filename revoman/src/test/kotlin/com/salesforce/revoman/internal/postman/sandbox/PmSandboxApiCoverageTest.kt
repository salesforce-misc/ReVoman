/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Per-feature coverage of the script-only `pm` APIs ReVoman commits to supporting in Phase 1,
 * grouped by Postman API area. Each test runs the REAL postman-sandbox bootcode under GraalJS
 * (boots once for the class) so it characterizes the actual behavior, not a shim.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PmSandboxApiCoverageTest {
  private val sandbox = PmSandbox()

  @AfterAll fun tearDown() = sandbox.close()

  private fun runTest(
    script: String,
    env: Map<String, Any?> = emptyMap(),
    collectionVariables: Map<String, Any?> = emptyMap(),
    request: Map<String, Any?>? = null,
    response: Map<String, Any?>? = null,
  ): PmExecutionResult =
    sandbox.execute(
      script,
      ScriptTarget.TEST,
      PmExecutionContext(
        environment = PmScope("e", env),
        collectionVariables = PmScope("cv", collectionVariables),
        request = request,
        response = response,
      ),
    )

  // ---------------------------------------------------------------- Variables

  @Test
  fun `pm collectionVariables get reads a seeded value`() {
    val r =
      runTest(
        "pm.test('get', () => pm.expect(pm.collectionVariables.get('seed')).to.eql('s1'));",
        collectionVariables = mapOf("seed" to "s1"),
      )
    r.error shouldBe null
    r.assertions[0].passed shouldBe true
  }

  @Test
  fun `pm collectionVariables set round-trips back to host`() {
    val r = runTest("pm.collectionVariables.set('produced', 'p1');")
    r.error shouldBe null
    r.collectionVariables["produced"] shouldBe "p1"
  }

  @Test
  fun `pm collectionVariables toObject returns all as a plain object`() {
    val r =
      runTest(
        """
        pm.collectionVariables.set('b', '2');
        const all = pm.collectionVariables.toObject();
        pm.test('toObject has seeded key', () => pm.expect(all.a).to.eql('1'));
        pm.test('toObject has set key', () => pm.expect(all.b).to.eql('2'));
        """
          .trimIndent(),
        collectionVariables = mapOf("a" to "1"),
      )
    r.error shouldBe null
    r.assertions[0].passed shouldBe true
    r.assertions[1].passed shouldBe true
  }

  // ---------------------------------------------------------------- Environment

  @Test
  fun `pm environment name is exposed to the script`() {
    val r =
      sandbox.execute(
        "pm.test('name', () => pm.expect(pm.environment.name).to.eql('Pokemon Env'));",
        ScriptTarget.TEST,
        PmExecutionContext(environment = PmScope("env-id", emptyMap(), name = "Pokemon Env")),
      )
    r.error shouldBe null
    r.assertions[0].passed shouldBe true
  }

  // ---------------------------------------------------------------- Request / Response

  @Test
  fun `pm request body raw is readable in a test script`() {
    val r =
      runTest(
        "pm.test('body', () => pm.expect(pm.request.body.raw).to.include('bulbasaur'));",
        request =
          mapOf(
            "method" to "POST",
            "url" to "https://example.com",
            "body" to mapOf("mode" to "raw", "raw" to """{"name":"bulbasaur"}"""),
          ),
      )
    r.error shouldBe null
    r.assertions[0].passed shouldBe true
  }

  @Test
  fun `pm response code returns the HTTP status code`() {
    val r =
      runTest(
        "pm.test('code', () => pm.expect(pm.response.code).to.eql(200));",
        response = mapOf("code" to 200, "status" to "OK", "body" to "{}"),
      )
    r.error shouldBe null
    r.assertions[0].passed shouldBe true
  }

  @Test
  fun `pm response json parses the response body`() {
    val r =
      runTest(
        "pm.test('json', () => pm.expect(pm.response.json().x).to.eql(7));",
        response = mapOf("code" to 200, "status" to "OK", "body" to """{"x":7}"""),
      )
    r.error shouldBe null
    r.assertions[0].passed shouldBe true
  }

  @Test
  fun `pm response text returns the raw body string`() {
    val r =
      runTest(
        "pm.test('text', () => pm.expect(pm.response.text()).to.eql('{\"x\":7}'));",
        response = mapOf("code" to 200, "status" to "OK", "body" to """{"x":7}"""),
      )
    r.error shouldBe null
    r.assertions[0].passed shouldBe true
  }

  @Test
  fun `pm response to have status asserts on the status code`() {
    val r =
      runTest(
        "pm.test('status', () => pm.response.to.have.status(200));",
        response = mapOf("code" to 200, "status" to "OK", "body" to "{}"),
      )
    r.error shouldBe null
    r.assertions[0].passed shouldBe true
  }

  // ---------------------------------------------------------------- Testing / Assertions

  @Test
  fun `pm test and pm expect record a passing assertion`() {
    val r = runTest("pm.test('expect', () => pm.expect(1 + 1).to.eql(2));")
    r.error shouldBe null
    r.assertions[0].passed shouldBe true
  }

  // ---------------------------------------------------------------- Execution Control

  @Test
  fun `pm execution setNextRequest is captured in the result`() {
    val r = runTest("pm.execution.setNextRequest('color-step');")
    r.error shouldBe null
    r.nextRequest shouldBe "color-step"
  }
}
