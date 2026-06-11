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

  /** The active environment's display name, exposed to scripts via `pm.environment.name`. */
  @JvmField var environmentName: String? = null

  // Per-step capture written by PmJsEval after each sandbox run, read by the executor fold when it
  // builds the StepReport. Keyed by Step (a step can run a pre-req AND a post-res script).
  private val pmTestAssertionsByStep: MutableMap<Step, List<PmTestAssertion>> = mutableMapOf()
  private val nextRequestByStep: MutableMap<Step, String?> = mutableMapOf()

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
    private var imports = ""

    init {
      val options = buildMap {
        if (!nodeModulesPath.isNullOrBlank()) {
          logger.info { "nodeModulesPath: $nodeModulesPath" }
          put("js.commonjs-require", "true")
          put("js.commonjs-require-cwd", nodeModulesPath)
          imports = "var _ = require('lodash')\n"
        }
        put("js.esm-eval-returns-exports", "true")
        put("engine.WarnInterpreterOnly", "false")
      }
      jsContext =
        Context.newBuilder("js")
          .allowExperimentalOptions(true)
          .allowIO(IOAccess.ALL)
          .allowExperimentalOptions(true)
          .options(options)
          .allowHostAccess(HostAccess.ALL)
          .allowHostClassLookup { true }
          .build()
      val contextBindings = jsContext.getBindings("js")
      contextBindings.putMember("pm", this@PostmanSDK)
      contextBindings.putMember("xml2Json", this@PostmanSDK.xml2Json)
    }

    internal fun evaluateJS(js: String, bindings: Map<String, Any> = emptyMap()): Value {
      val contextBindings = jsContext.getBindings("js")
      bindings.forEach { (key, value) -> contextBindings.putMember(key, value) }
      val jsSource = Source.newBuilder("js", imports + js, "script.js").build()
      // ! TODO gopala.akshintala 15/05/22: Keep a tab on jsContext mix-up from different steps
      return jsContext.eval(jsSource)
    }
  }

  internal fun syncProgress(stepReport: StepReport) {
    currentStepReport = stepReport
    rundown = rundown.copy(stepReports = rundown.stepReports + stepReport)
  }

  /** Accumulates assertions across a step's pre-req + post-res scripts. */
  internal fun recordPmTestAssertions(step: Step, assertions: List<PmTestAssertion>) {
    pmTestAssertionsByStep[step] = (pmTestAssertionsByStep[step] ?: emptyList()) + assertions
  }

  internal fun pmTestAssertionsFor(step: Step): List<PmTestAssertion> =
    pmTestAssertionsByStep[step] ?: emptyList()

  /**
   * Last-write-wins: a post-res `setNextRequest` overrides a pre-req one (matches Postman). A null
   * (never recorded) and an explicit `setNextRequest(null)` clear are intentionally
   * indistinguishable — both mean "no jump".
   */
  internal fun recordNextRequest(step: Step, nextRequest: String?) {
    nextRequestByStep[step] = nextRequest
  }

  internal fun nextRequestFor(step: Step): String? = nextRequestByStep[step]

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

  @Language("JavaScript")
  fun jsonStrToObj(jsonStr: String): Value =
    evaluateJS("jsonStr => JSON.parse(jsonStr, {allowComments: true})").execute(jsonStr)

  inner class Variables {
    fun has(key: String): Boolean = environment.containsKey(key)

    fun get(key: String): Any? = environment[key]

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
