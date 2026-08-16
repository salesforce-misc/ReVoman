/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either.Right
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.PostmanVariableScopes
import com.salesforce.revoman.internal.postman.StepScriptCapture
import com.salesforce.revoman.internal.postman.postmanVariableScopes
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext
import com.salesforce.revoman.internal.postman.sandbox.PmExecutionResult
import com.salesforce.revoman.internal.postman.sandbox.PmSandbox
import com.salesforce.revoman.internal.postman.sandbox.ScriptTarget
import com.salesforce.revoman.internal.postman.stepScriptCapture
import com.salesforce.revoman.internal.postman.template.Body
import com.salesforce.revoman.internal.postman.template.Event
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.internal.runtime.ScriptExecutor
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxnInfo
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.http4k.core.Response
import org.http4k.core.Status.Companion.ACCEPTED
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Exercises the real sandbox and the immediate per-phase scope/capture apply-back boundary. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PmJsEvalScopesDiffTest {
  private val sandbox = PmSandbox()

  @AfterAll fun tearDown() = sandbox.close()

  @Test
  fun `pm globals set is diffed back to the globals store`() {
    val state = runPreReq("pm.globals.set('g', '1');")
    state.scopes.globals["g"] shouldBe "1"
  }

  @Test
  fun `pm globals unset of a seeded global removes it`() {
    val state = runPreReq("pm.globals.unset('seed');", globals = mapOf("seed" to "s1"))
    state.scopes.globals.containsKey("seed") shouldBe false
  }

  @Test
  fun `pm globals seeded value is readable and a changed value diffs back`() {
    val state =
      runPreReq(
        "pm.globals.set('seed', pm.globals.get('seed') + '-changed');",
        globals = mapOf("seed" to "s1"),
      )
    state.scopes.globals["seed"] shouldBe "s1-changed"
  }

  @Test
  fun `all three scopes diff back to their peer with no cross-contamination`() {
    val state =
      runPreReq(
        """
        pm.environment.set('e', 'env');
        pm.collectionVariables.set('c', 'cv');
        pm.globals.set('g', 'glob');
        """
          .trimIndent()
      )
    val scopes = state.scopes
    scopes.environment["e"] shouldBe "env"
    scopes.collectionVariables["c"] shouldBe "cv"
    scopes.globals["g"] shouldBe "glob"
    scopes.environment.containsKey("c") shouldBe false
    scopes.environment.containsKey("g") shouldBe false
    scopes.collectionVariables.containsKey("e") shouldBe false
    scopes.collectionVariables.containsKey("g") shouldBe false
    scopes.globals.containsKey("e") shouldBe false
    scopes.globals.containsKey("c") shouldBe false
  }

  @Test
  fun `unchanged seeded globals survive a script that touches only environment`() {
    val state = runPreReq("pm.environment.set('e', '1');", globals = mapOf("keep" to "k1"))
    state.scopes.globals shouldContainExactly mapOf("keep" to "k1")
  }

  @Test
  fun `pm variables get resolves scope precedence inside the sandbox`() {
    val state =
      runPreReq(
        "pm.environment.set('out', pm.variables.get('k'));",
        environment = mapOf("k" to "env"),
        globals = mapOf("k" to "global"),
      )
    state.scopes.environment["out"] shouldBe "env"
  }

  @Test
  fun `globals stay empty when no script touches them`() {
    val state = runPreReq("pm.environment.set('e', '1');")
    state.scopes.globals.toMap().shouldBeEmpty()
  }

  @Test
  fun `setNextRequest and skipRequest directives are captured per step`() {
    val state = runPreReq("pm.execution.setNextRequest('z'); pm.execution.skipRequest();")
    val step = step()
    state.capture.nextRequestFor(step) shouldBe "z"
    state.capture.nextRequestWasSetFor(step) shouldBe true
    state.capture.skipRequestFor(step) shouldBe true
  }

  @Test
  fun `setNextRequest null retains the separate was-set bit`() {
    val state = runPreReq("pm.execution.setNextRequest(null);")
    val step = step()
    state.capture.nextRequestFor(step) shouldBe null
    state.capture.nextRequestWasSetFor(step) shouldBe true
    state.capture.skipRequestFor(step) shouldBe false
  }

  @Test
  fun `pre-request adapter maps distinctive JSON and raw request body into the real sandbox`() {
    val requestJson = """{"requestMarker":"pm-js-request","nested":{"count":17}}"""
    val request =
      Request(
        method = "POST",
        url = Url("https://adapter.invalid/request"),
        body = Body(mode = "raw", raw = requestJson),
      )
    val item =
      Item(
        name = "adapter",
        request = request,
        event =
          listOf(
            Event(
              "prerequest",
              Event.Script(
                """
                const parsed = pm.request.json();
                pm.test('request adapter json', () => {
                  pm.expect(parsed.requestMarker).to.eql('pm-js-request');
                  pm.expect(parsed.nested.count).to.eql(17);
                });
                pm.test('request adapter raw', () => {
                  pm.expect(pm.request.body.raw).to.eql('$requestJson');
                });
                """
                  .trimIndent()
                  .split("\n")
              ),
            )
          ),
      )
    val step = Step(index = "1", rawPMStep = item)
    val state = state()

    val result = executePreReqJS(step, item, mockk(), state.scopes, state.capture, sandbox)

    result.isRight() shouldBe true
    val assertions = state.capture.assertionsFor(step)
    assertions.map { it.name } shouldBe listOf("request adapter json", "request adapter raw")
    assertions.all { it.passed } shouldBe true
  }

  @Test
  fun `pre-request adapter preserves bodyless request json as null`() {
    val item =
      Item(
        name = "bodyless",
        request = Request(method = "GET", url = Url("https://adapter.invalid/bodyless")),
        event =
          listOf(
            Event(
              "prerequest",
              Event.Script(
                listOf(
                  "pm.test('bodyless request json', () => pm.expect(pm.request.json()).to.eql(null));"
                )
              ),
            )
          ),
      )
    val step = Step(index = "1", rawPMStep = item)
    val state = state()

    val result = executePreReqJS(step, item, mockk(), state.scopes, state.capture, sandbox)

    result.isRight() shouldBe true
    state.capture.assertionsFor(step).single().passed shouldBe true
  }

  @Test
  fun `post-response adapter maps distinctive JSON status code and text into the real sandbox`() {
    val request = Request(method = "GET", url = Url("https://adapter.invalid/response"))
    val responseJson = """{"responseMarker":"pm-js-response","nested":{"count":29}}"""
    val item =
      Item(
        name = "adapter",
        request = request,
        event =
          listOf(
            Event(
              "test",
              Event.Script(
                """
                const parsed = pm.response.json();
                pm.test('response adapter json', () => {
                  pm.expect(parsed.responseMarker).to.eql('pm-js-response');
                  pm.expect(parsed.nested.count).to.eql(29);
                });
                pm.test('response adapter status code text', () => {
                  pm.expect(pm.response.status).to.eql('202 Accepted');
                  pm.expect(pm.response.code).to.eql(202);
                  pm.expect(pm.response.text()).to.eql('$responseJson');
                  pm.response.to.have.status(202);
                });
                """
                  .trimIndent()
                  .split("\n")
              ),
            )
          ),
      )
    val step = Step(index = "1", rawPMStep = item)
    val state = state()
    val moshi = initMoshi()
    val report =
      StepReport(
        step = step,
        requestInfo = Right(TxnInfo(httpMsg = request.toHttpRequest(moshi), moshiReVoman = moshi)),
        responseInfo =
          Right(
            TxnInfo(
              httpMsg = Response(ACCEPTED).body(responseJson),
              moshiReVoman = moshi,
            )
          ),
        pmEnvSnapshot = state.scopes.environment,
      )

    val result = executePostResJS(step, item, report, state.scopes, state.capture, sandbox)

    result.isRight() shouldBe true
    val assertions = state.capture.assertionsFor(step)
    assertions.map { it.name } shouldBe
      listOf("response adapter json", "response adapter status code text")
    assertions.all { it.passed } shouldBe true
  }

  @Test
  fun `absent pre-request script returns without invoking the borrowed executor`() {
    val executor = CountingThrowingExecutor()
    val state = state()
    val result =
      executePreReqJS(
        step(),
        Item(name = "s", request = Request(method = "GET")),
        mockk(),
        state.scopes,
        state.capture,
        executor,
      )

    result.isRight() shouldBe true
    executor.calls shouldBe 0
  }

  @Test
  fun `blank pre-request script returns without invoking the borrowed executor`() {
    val executor = CountingThrowingExecutor()
    val state = state()
    val item =
      Item(
        name = "s",
        request = Request(method = "GET"),
        event = listOf(Event("prerequest", Event.Script(listOf(" ", "\t")))),
      )
    val result = executePreReqJS(step(), item, mockk(), state.scopes, state.capture, executor)

    result.isRight() shouldBe true
    executor.calls shouldBe 0
  }

  @Test
  fun `absent test script returns without invoking the borrowed executor`() {
    val executor = CountingThrowingExecutor()
    val state = state()
    val result =
      executePostResJS(
        step(),
        Item(name = "s", request = Request(method = "GET")),
        mockk(),
        state.scopes,
        state.capture,
        executor,
      )

    result.isRight() shouldBe true
    executor.calls shouldBe 0
  }

  @Test
  fun `blank test script returns before response access and without invoking executor`() {
    val executor = CountingThrowingExecutor()
    val state = state()
    val item =
      Item(
        name = "s",
        request = Request(method = "GET"),
        event = listOf(Event("test", Event.Script(listOf(" ", "\t")))),
      )
    val result =
      executePostResJS(
        step(),
        item,
        mockk(),
        state.scopes,
        state.capture,
        executor,
      )

    result.isRight() shouldBe true
    executor.calls shouldBe 0
  }

  private fun runPreReq(
    script: String,
    environment: Map<String, Any?> = emptyMap(),
    collectionVariables: Map<String, Any?> = emptyMap(),
    globals: Map<String, Any?> = emptyMap(),
  ): State {
    val state = state(environment, collectionVariables, globals)
    val item =
      Item(
        name = "s",
        request = Request(method = "GET"),
        event = listOf(Event("prerequest", Event.Script(script.split("\n")))),
      )
    val result = executePreReqJS(step(), item, mockk(), state.scopes, state.capture, sandbox)
    result.isRight() shouldBe true
    return state
  }

  private fun state(
    environment: Map<String, Any?> = emptyMap(),
    collectionVariables: Map<String, Any?> = emptyMap(),
    globals: Map<String, Any?> = emptyMap(),
  ): State {
    val moshi = initMoshi()
    return State(
      scopes =
        postmanVariableScopes(
          PostmanEnvironment(environment.toMutableMap(), moshi),
          PostmanEnvironment(collectionVariables.toMutableMap(), moshi),
          PostmanEnvironment(globals.toMutableMap(), moshi),
          environmentName = "test",
        ),
      capture = stepScriptCapture(),
    )
  }

  private fun step(): Step = Step(index = "1", rawPMStep = Item(name = "s"))

  private data class State(
    val scopes: PostmanVariableScopes,
    val capture: StepScriptCapture,
  )

  private class CountingThrowingExecutor : ScriptExecutor {
    var calls = 0

    override fun execute(
      script: String,
      target: ScriptTarget,
      context: PmExecutionContext,
      timeoutMs: Long,
    ): PmExecutionResult {
      calls++
      error("blank or absent scripts must not invoke the borrowed executor")
    }
  }
}
