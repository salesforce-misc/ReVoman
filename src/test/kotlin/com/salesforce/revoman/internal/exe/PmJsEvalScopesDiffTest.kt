/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.internal.postman.sandbox.PmSandbox
import com.salesforce.revoman.internal.postman.template.Event
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.output.report.Step
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Characterizes the full PmJsEval diff-back wiring through the REAL postman-sandbox (boots once for
 * the class): a script mutates `pm.globals` / `pm.collectionVariables` / `pm.environment`, and the
 * mutations must land on the matching peer store on [PostmanSDK] with no cross-contamination.
 * Drives the public [executePreReqJS] entry point, not the private sandbox helper, so it exercises
 * the same code path a real `revUp()` run does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PmJsEvalScopesDiffTest {
  private val sandbox = PmSandbox()

  @AfterAll fun tearDown() = sandbox.close()

  private fun step(name: String) = Step(index = "1", rawPMStep = Item(name = name))

  /**
   * Runs [preReqScript] as a pre-request script against a fresh SDK seeded with the given scopes.
   */
  private fun runPreReq(
    preReqScript: String,
    env: Map<String, Any?> = emptyMap(),
    collectionVariables: Map<String, Any?> = emptyMap(),
    globals: Map<String, Any?> = emptyMap(),
  ): PostmanSDK {
    val pm = PostmanSDK(initMoshi())
    env.forEach { (k, v) -> pm.environment.set(k, v) }
    collectionVariables.forEach { (k, v) -> pm.collectionVariables.set(k, v) }
    globals.forEach { (k, v) -> pm.globals.set(k, v) }
    pm.currentStepReport = mockk()
    val item =
      Item(
        name = "s",
        request = Request(method = "GET"),
        event = listOf(Event("prerequest", Event.Script(preReqScript.split("\n")))),
      )
    val result = executePreReqJS(step("s"), item, mockk(), pm, sandbox)
    result.isRight() shouldBe true
    return pm
  }

  @Test
  fun `pm globals set is diffed back to the globals store`() {
    val pm = runPreReq("pm.globals.set('g', '1');")
    pm.globals["g"] shouldBe "1"
  }

  @Test
  fun `pm globals unset of a seeded global removes it`() {
    val pm = runPreReq("pm.globals.unset('seed');", globals = mapOf("seed" to "s1"))
    pm.globals.containsKey("seed") shouldBe false
  }

  @Test
  fun `pm globals seeded value is readable in the script and a changed value diffs back`() {
    val pm =
      runPreReq(
        "pm.globals.set('seed', pm.globals.get('seed') + '-changed');",
        globals = mapOf("seed" to "s1"),
      )
    pm.globals["seed"] shouldBe "s1-changed"
  }

  @Test
  fun `all three scopes mutated in one script diff back to the right peer with no cross-contamination`() {
    val pm =
      runPreReq(
        """
        pm.environment.set('e', 'env');
        pm.collectionVariables.set('c', 'cv');
        pm.globals.set('g', 'glob');
        """
          .trimIndent()
      )
    pm.environment["e"] shouldBe "env"
    pm.collectionVariables["c"] shouldBe "cv"
    pm.globals["g"] shouldBe "glob"
    // No key bleeds across stores.
    pm.environment.containsKey("c") shouldBe false
    pm.environment.containsKey("g") shouldBe false
    pm.collectionVariables.containsKey("e") shouldBe false
    pm.collectionVariables.containsKey("g") shouldBe false
    pm.globals.containsKey("e") shouldBe false
    pm.globals.containsKey("c") shouldBe false
  }

  @Test
  fun `unchanged seeded globals survive a script that touches nothing`() {
    val pm = runPreReq("pm.environment.set('e', '1');", globals = mapOf("keep" to "k1"))
    pm.globals shouldContainExactly mapOf("keep" to "k1")
  }

  @Test
  fun `pm variables get inside the script resolves globals by precedence`() {
    // env absent, cv absent, globals has it → pm.variables.get falls through to globals (sandbox's
    // own aggregate). Proven by writing the resolved value into env for the host to read back.
    val pm =
      runPreReq(
        "pm.environment.set('out', pm.variables.get('only'));",
        globals = mapOf("only" to "g1"),
      )
    pm.environment["out"] shouldBe "g1"
  }

  @Test
  fun `pm variables get prefers environment over globals inside the script`() {
    val pm =
      runPreReq(
        "pm.environment.set('out', pm.variables.get('k'));",
        env = mapOf("k" to "env"),
        globals = mapOf("k" to "glob"),
      )
    pm.environment["out"] shouldBe "env"
  }

  @Test
  fun `globals store stays empty when no script touches it`() {
    val pm = runPreReq("pm.environment.set('e', '1');")
    pm.globals.toMap().shouldBeEmpty()
  }
}
