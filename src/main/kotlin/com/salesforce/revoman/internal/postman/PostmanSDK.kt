/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.github.underscore.U
import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.internal.postman.sandbox.sharedGraalEngine
import com.salesforce.revoman.internal.postman.template.Body
import com.salesforce.revoman.internal.postman.template.Event
import com.salesforce.revoman.internal.postman.template.Header
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.PmTestAssertion
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import io.exoquery.pprint
import io.github.oshai.kotlinlogging.KotlinLogging
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.intellij.lang.annotations.Language

/**
 * SDK to execute pre-req and post-res js scripts, to be compatible with the Postman API reference:
 * https://learning.postman.com/docs/writing-scripts/script-references/postman-sandbox-api-reference/
 */
class PostmanSDK(
  private val moshiReVoman: MoshiReVoman,
  nodeModulesPath: String? = null,
  internal val regexReplacer: RegexReplacer = RegexReplacer(),
  mutableEnv: MutableMap<String, Any?> = mutableMapOf(),
) {
  @JvmField val environment: PostmanEnvironment<Any?> = PostmanEnvironment(mutableEnv, moshiReVoman)

  /**
   * Collection-level variables (`pm.collectionVariables`). A plain key→value store reusing
   * [PostmanEnvironment] for its set/unset/toMap utilities; its per-step ledger capture stays
   * dormant because [PostmanEnvironment.currentStep] is never set on this instance. Script-seeded
   * only (ReVoman does not parse collection-root `variable[]`), so it starts empty and is populated
   * by scripts calling `pm.collectionVariables.set(...)`.
   */
  @JvmField
  val collectionVariables: PostmanEnvironment<Any?> =
    PostmanEnvironment(mutableMapOf(), moshiReVoman)

  /**
   * Global variables (`pm.globals`). A peer of [environment] and [collectionVariables] reusing
   * [PostmanEnvironment] for its set/unset/toMap utilities; its per-step ledger capture stays
   * dormant because [PostmanEnvironment.currentStep] is never set on this instance (so `set()` logs
   * `Step: null` and records no produced keys). Script-seeded only (ReVoman does not parse a
   * Postman globals export), so it starts empty and is populated by scripts calling
   * `pm.globals.set(...)`.
   */
  @JvmField val globals: PostmanEnvironment<Any?> = PostmanEnvironment(mutableMapOf(), moshiReVoman)

  /** The active environment's display name, exposed to scripts via `pm.environment.name`. */
  @JvmField var environmentName: String? = null

  /**
   * Postman variable-scope precedence (narrowest wins): `environment` ▸ `collectionVariables` ▸
   * `globals`. Returns the value from the first scope that *contains* [key], or `null` when no
   * scope knows it. A scope holding `key=null` still counts as containing it (presence ≠ value) —
   * use [hasScopedValue] to distinguish. Single source of truth for both `pm.variables` and the
   * `{{key}}` regex resolution in [RegexReplacer].
   */
  internal fun resolveScopedValue(key: String): Any? =
    when {
      environment.containsKey(key) -> environment[key]
      collectionVariables.containsKey(key) -> collectionVariables[key]
      globals.containsKey(key) -> globals[key]
      else -> null
    }

  /** True when any scope (environment / collectionVariables / globals) contains [key]. */
  internal fun hasScopedValue(key: String): Boolean =
    environment.containsKey(key) || collectionVariables.containsKey(key) || globals.containsKey(key)

  /** The scope that owns [key] by precedence, or `null` if no scope contains it. */
  internal fun scopeForKey(key: String): PostmanEnvironment<Any?>? =
    when {
      environment.containsKey(key) -> environment
      collectionVariables.containsKey(key) -> collectionVariables
      globals.containsKey(key) -> globals
      else -> null
    }

  // Per-step capture written by PmJsEval after each sandbox run, read by the executor fold when it
  // builds the StepReport. Keyed by Step (a step can run a pre-req AND a post-res script).
  private val pmTestAssertionsByStep: MutableMap<Step, List<PmTestAssertion>> = mutableMapOf()
  private val nextRequestByStep: MutableMap<Step, String?> = mutableMapOf()
  private val nextRequestSetByStep: MutableMap<Step, Boolean> = mutableMapOf()
  private val skipRequestByStep: MutableMap<Step, Boolean> = mutableMapOf()

  lateinit var info: Info
  lateinit var request: Request
  lateinit var response: Response
  lateinit var currentStepReport: StepReport
  @Suppress("unused") @JvmField val variables: Variables = Variables()
  lateinit var rundown: Rundown
  @JvmField val xml2Json = Xml2Json { xml -> moshiReVoman.fromJson(U.xmlToJson(xml)) }
  // * NOTE 28 Apr 2024 gopala.akshintala: This has to be initialized at last
  private val jsEvaluator: JSEvaluator = JSEvaluator(nodeModulesPath)

  @SuppressWarnings("kotlin:S6517")
  @FunctionalInterface // DON'T REMOVE THIS. Polyglot won't work without this
  fun interface Xml2Json {
    @Suppress("unused") fun xml2Json(xml: String): Any?
  }

  inner class JSEvaluator(nodeModulesPath: String? = null) {
    private val jsContext: Context
    // Constant per-instance prefix, computed once. Non-empty only when a nodeModulesPath enabled
    // commonjs-require (so lodash `_` is importable). Hoisted to a val — never mutated after init.
    private val imports: String =
      if (!nodeModulesPath.isNullOrBlank()) "var _ = require('lodash')\n" else ""

    init {
      val options = buildMap {
        if (!nodeModulesPath.isNullOrBlank()) {
          logger.info { "nodeModulesPath: $nodeModulesPath" }
          put("js.commonjs-require", "true")
          put("js.commonjs-require-cwd", nodeModulesPath)
        }
        put("js.esm-eval-returns-exports", "true")
      }
      jsContext =
        Context.newBuilder("js")
          .engine(sharedGraalEngine)
          .allowExperimentalOptions(true)
          .allowIO(IOAccess.ALL)
          .options(options)
          .allowHostAccess(HostAccess.ALL)
          .allowHostClassLookup { true }
          .build()
      val contextBindings = jsContext.getBindings("js")
      contextBindings.putMember("pm", this@PostmanSDK)
      contextBindings.putMember("xml2Json", this@PostmanSDK.xml2Json)
    }

    // * NOTE: A `Map<String, Value>` result-memo for repeated identical scripts is INTENTIONALLY
    // NOT added here — it is unsafe. This fn injects per-call [bindings] into the shared Context,
    // and pm scripts routinely have side effects (`pm.environment.set(...)`) and run identical
    // text with different bindings (e.g. `xml2Json` over a different `responseBody`). A
    // script->Value cache would skip both binding injection and re-execution, corrupting behavior.
    // The safe repeated-script win is the closure memo (see [jsonParseFn]); A4 is limited to
    // making the [imports] prefix immutable.
    internal fun evaluateJS(js: String, bindings: Map<String, Any> = emptyMap()): Value {
      val contextBindings = jsContext.getBindings("js")
      bindings.forEach { (key, value) -> contextBindings.putMember(key, value) }
      val jsSource = Source.newBuilder("js", imports + js, "script.js").build()
      // ! TODO gopala.akshintala 15/05/22: Keep a tab on jsContext mix-up from different steps
      return jsContext.eval(jsSource)
    }
  }

  /**
   * Publishes the current step's evolving [StepReport] into [rundown] for mid-run readers (hooks,
   * the halt predicate). ReVoman seeds [rundown] with this step's pre-step report as the LAST
   * entry, then calls this 3x per step as the report gains request/response/hook detail. REPLACES
   * the current step's entry (matched as the last report for the same [Step]) rather than
   * appending, so a step appears exactly ONCE mid-run — earlier steps and prior loop iterations
   * (which sit before it) are untouched. Keeps [Rundown] immutable via [Rundown.copy].
   */
  internal fun syncProgress(stepReport: StepReport) {
    currentStepReport = stepReport
    val reports = rundown.stepReports
    val updated =
      if (reports.lastOrNull()?.step == stepReport.step) reports.dropLast(1) + stepReport
      else reports + stepReport
    rundown = rundown.copy(stepReports = updated)
  }

  /** Accumulates assertions across a step's pre-req + post-res scripts. */
  internal fun recordPmTestAssertions(step: Step, assertions: List<PmTestAssertion>) {
    pmTestAssertionsByStep[step] = (pmTestAssertionsByStep[step] ?: emptyList()) + assertions
  }

  internal fun pmTestAssertionsFor(step: Step): List<PmTestAssertion> =
    pmTestAssertionsByStep[step] ?: emptyList()

  /**
   * Last-write-wins: a post-res `setNextRequest` overrides a pre-req one (matches Postman). [set]
   * is true iff `setNextRequest` was called at all this phase, so the sequencer can tell an
   * explicit `setNextRequest(null)` (STOP) from "never called" (no directive). The latest phase's
   * [set] wins alongside its [nextRequest].
   */
  internal fun recordNextRequest(step: Step, nextRequest: String?, set: Boolean) {
    nextRequestByStep[step] = nextRequest
    nextRequestSetByStep[step] = set
  }

  internal fun nextRequestFor(step: Step): String? = nextRequestByStep[step]

  internal fun nextRequestSetFor(step: Step): Boolean = nextRequestSetByStep[step] ?: false

  /** Records a pre-request `pm.execution.skipRequest()` for [step]. */
  internal fun recordSkipRequest(step: Step) {
    skipRequestByStep[step] = true
  }

  internal fun skipRequestFor(step: Step): Boolean = skipRequestByStep[step] ?: false

  /**
   * Clears this step's per-execution control-flow + assertion capture. Called at the start of each
   * execution so a step that runs more than once (a setNextRequest loop) does not inherit the prior
   * iteration's directive/assertions. No-op on a step's first (or only) execution, so the common
   * non-looping path is unaffected.
   */
  internal fun resetCaptureForStep(step: Step) {
    nextRequestByStep.remove(step)
    nextRequestSetByStep.remove(step)
    skipRequestByStep.remove(step)
    pmTestAssertionsByStep.remove(step)
  }

  internal fun setRequestAndResponse(pmRequest: Request, httpResponse: org.http4k.core.Response) {
    request = pmRequest
    response =
      Response(httpResponse.status.code, httpResponse.status.toString(), httpResponse.bodyString())
  }

  internal fun setRequest(pmRequest: Request) {
    request = pmRequest
  }

  internal fun setResponse(httpResponse: org.http4k.core.Response) {
    response =
      Response(httpResponse.status.code, httpResponse.status.toString(), httpResponse.bodyString())
  }

  internal fun setResponse(code: Int, status: String, body: String) {
    response = Response(code, status, body)
  }

  fun evaluateJS(js: String, bindings: Map<String, Any> = emptyMap()): Value =
    jsEvaluator.evaluateJS(js, bindings)

  // Memoized JSON.parse closure: a stateless guest function bound to this instance's jsContext.
  // Parsing the function literal once (not per call) skips a Source parse + compile on every
  // json()/jsonStrToObj call. Reuse is safe — the closure holds no per-call state; only its
  // argument varies. `by lazy` defers forcing until the first parse (jsEvaluator is init'd last).
  private val jsonParseFn: Value by lazy {
    @Language("JavaScript")
    val jsonParseArrow = "jsonStr => JSON.parse(jsonStr, {allowComments: true})"
    evaluateJS(jsonParseArrow)
  }

  fun jsonStrToObj(jsonStr: String): Value = jsonParseFn.execute(jsonStr)

  /**
   * `pm.variables` — the aggregate READ view across all scopes, honoring Postman precedence
   * (`environment` ▸ `collectionVariables` ▸ `globals`). Reads ([has]/[get]) walk the chain; writes
   * ([set]/[unset]) target the [environment] scope. Real Postman's `pm.variables.set` writes the
   * ephemeral *local* scope, which ReVoman does not model — environment is the closest persistent
   * analog, and routing there preserves long-standing behavior (see design D1).
   */
  inner class Variables {
    fun has(key: String): Boolean = hasScopedValue(key)

    fun get(key: String): Any? = resolveScopedValue(key)

    fun set(key: String, value: Any?) {
      environment.set(key, value)
      logger.info {
        "pm environment variable set through JS in Step: ${currentStepReport.step} - key: $key, value: ${pprint(value)}"
      }
    }

    fun unset(key: String) {
      environment.unset(key)
      logger.info {
        "pm environment variable unset through JS in Step: ${currentStepReport.step} - key: $key"
      }
    }

    @Suppress("unused")
    fun replaceIn(stringToReplace: String): String =
      regexReplacer.replaceVariablesRecursively(stringToReplace, this@PostmanSDK) ?: ""
  }

  inner class Request(
    @JvmField val method: String = "",
    @JvmField val header: List<Header> = emptyList(),
    @JvmField val url: Url = Url(),
    @JvmField val body: Body? = null,
    @JvmField val event: List<Event>? = null,
  ) {
    fun json(): Value? = body?.raw?.let { jsonStrToObj(it) }

    fun copy(header: List<Header>, url: Url, body: Body?): Request =
      Request(header = header, url = url, body = body)
  }

  fun from(request: com.salesforce.revoman.internal.postman.template.Request): Request =
    Request(request.method, request.header, request.url, request.body, request.event)

  inner class Response(
    @JvmField val code: Int,
    @JvmField val status: String,
    @JvmField val body: String,
  ) {
    /**
     * This is implemented using
     * [Functions as Java Values](https://www.graalvm.org/22.3/reference-manual/embed-languages/#define-guest-language-functions-as-java-values):
     */
    fun json(): Value = jsonStrToObj(body)

    fun text(): String = body
  }

  @Suppress("unused")
  fun setEnvironmentVariable(key: String, value: String) {
    environment.set(key, value)
  }
}

data class Info(@JvmField val requestName: String)

private val logger = KotlinLogging.logger {}
