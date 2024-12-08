/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.github.underscore.U
import com.salesforce.revoman.internal.postman.template.Body
import com.salesforce.revoman.internal.postman.template.Event
import com.salesforce.revoman.internal.postman.template.Header
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.StepReport
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.http4k.format.ConfigurableMoshi

/**
 * SDK to execute pre-req and post-res js scripts, to be compatible with the Postman API reference:
 * https://learning.postman.com/docs/writing-scripts/script-references/postman-sandbox-api-reference/
 */
class PostmanSDK(
  private val moshiReVoman: ConfigurableMoshi,
  nodeModulesRelativePath: String? = null,
  val regexReplacer: RegexReplacer = RegexReplacer(),
  mutableEnv: MutableMap<String, Any?> = mutableMapOf()
) {
  @JvmField val environment: PostmanEnvironment<Any?> = PostmanEnvironment(mutableEnv)
  lateinit var info: Info
  lateinit var request: Request
  lateinit var response: Response
  lateinit var currentStepReport: StepReport
  @JvmField val variables: Variables = Variables()
  lateinit var rundown: Rundown
  @JvmField val xml2Json = Xml2Json { xml -> moshiReVoman.asA(U.xmlToJson(xml)) }
  // * NOTE 28 Apr 2024 gopala.akshintala: This has to be initialized at last
  private val jsEvaluator: JSEvaluator = JSEvaluator(nodeModulesRelativePath)

  @SuppressWarnings("kotlin:S6517")
  @FunctionalInterface // DON'T REMOVE THIS. Polyglot won't work without this
  fun interface Xml2Json {
    @Suppress("unused") fun xml2Json(xml: String): Any?
  }

  inner class JSEvaluator(nodeModulesRelativePath: String? = null) {
    private val jsContext: Context
    private var imports = ""

    init {
      val options = buildMap {
        if (!nodeModulesRelativePath.isNullOrBlank()) {
          put("js.commonjs-require", "true")
          put("js.commonjs-require-cwd", nodeModulesRelativePath)
          imports = "var _ = require('lodash')\n"
        }
        put("js.esm-eval-returns-exports", "true")
        put("engine.WarnInterpreterOnly", "false")
      }
      jsContext =
        Context.newBuilder("js")
          .allowExperimentalOptions(true)
          // ! TODO 07 Dec 2024 gopala.akshintala: Using this for core compatability
          .allowIO(true)
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

  fun jsonStrToObj(jsonStr: String): Value =
    evaluateJS("jsonStr => JSON.parse(jsonStr)").execute(jsonStr)

  inner class Variables {
    fun has(variableKey: String) = environment.containsKey(variableKey)

    fun get(variableKey: String) = environment[variableKey]

    fun set(variableKey: String, value: String) {
      environment.set(variableKey, value)
    }

    fun replaceIn(stringToReplace: String): String =
      regexReplacer.replaceVariablesRecursively(stringToReplace, this@PostmanSDK) ?: ""
  }

  inner class Request(
    @JvmField val method: String = "",
    @JvmField val header: List<Header> = emptyList(),
    @JvmField val url: Url = Url(),
    @JvmField val body: Body? = null,
    @JvmField val event: List<Event>? = null
  ) {
    fun json(): Value? = body?.raw?.let { jsonStrToObj(it) }

    fun copy(header: List<Header>, url: Url, body: Body?): Request =
      Request(header = header, url = url, body = body)
  }

  fun from(request: com.salesforce.revoman.internal.postman.template.Request): PostmanSDK.Request =
    Request(request.method, request.header, request.url, request.body, request.event)

  inner class Response(
    @JvmField val code: Int,
    @JvmField val status: String,
    @JvmField val body: String
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
