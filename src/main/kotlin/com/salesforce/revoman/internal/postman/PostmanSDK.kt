/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.github.underscore.U
import com.salesforce.revoman.input.template.TemplateFormat
import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.internal.postman.template.Body
import com.salesforce.revoman.internal.postman.template.Event
import com.salesforce.revoman.internal.postman.template.Header
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
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
  internal var templateFormat: TemplateFormat = TemplateFormat.POSTMAN_JSON
  internal var jetbrainsEnvironment: Map<String, Any?> = emptyMap()
  internal var jetbrainsFileVariables: Map<String, Any?> = emptyMap()
  internal val requestVariables: MutableMap<String, Any?> = mutableMapOf()
  internal val globalHeaders: MutableMap<String, String> = mutableMapOf()
  private val jetbrainsClient = JetbrainsClient()
  private val jetbrainsRequest = JetbrainsRequest()
  private val jetbrainsResponse = JetbrainsResponse()
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
    private val persistentBindings = mutableSetOf<String>()

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
      initializePersistentBindings()
    }

    private fun initializePersistentBindings() {
      val contextBindings = jsContext.getBindings("js")
      contextBindings.putMember("pm", this@PostmanSDK)
      contextBindings.putMember("xml2Json", this@PostmanSDK.xml2Json)
      contextBindings.putMember("client", jetbrainsClient)
      contextBindings.putMember("request", jetbrainsRequest)
      contextBindings.putMember("response", jetbrainsResponse)
      contextBindings.putMember("\$env", System.getenv())
      // Track persistent bindings
      persistentBindings.addAll(listOf("pm", "xml2Json", "client", "request", "response", "\$env"))
    }

    /**
     * Resets the JavaScript context by clearing user-defined variables
     * while preserving the SDK bindings (pm, xml2Json, client, request, response, $env)
     * 
     * Note: Due to GraalVM limitations, we can't actually remove global variables,
     * but we can set them to undefined to effectively clear them.
     */
    internal fun resetContext() {
      // Evaluate a script to get all global properties
      val script = """
        (function() {
          var globalKeys = Object.keys(this);
          var persistentKeys = ${persistentBindings.joinToString(", ", "[", "]") { "\"$it\"" }};
          var keysToReset = [];
          for (var i = 0; i < globalKeys.length; i++) {
            var key = globalKeys[i];
            if (persistentKeys.indexOf(key) === -1) {
              keysToReset.push(key);
            }
          }
          // Set non-persistent globals to undefined
          for (var j = 0; j < keysToReset.length; j++) {
            this[keysToReset[j]] = undefined;
          }
          return keysToReset;
        }).call(this)
      """.trimIndent()
      
      jsContext.eval("js", script)
    }

    /**
     * Evaluates JavaScript in an isolated scope to prevent variable pollution
     * while preserving access to persistent SDK bindings
     */
    internal fun evaluateJSIsolated(js: String, bindings: Map<String, Any> = emptyMap()): Value {
      // First, clear any existing user-defined variables to prevent pollution
      resetContext()
      
      // Add temporary bindings for this execution
      val contextBindings = jsContext.getBindings("js")
      bindings.forEach { (key, value) -> contextBindings.putMember(key, value) }
      
      // Execute the script with a wrapper to capture and clean up temporary variables
      val wrappedScript = """
        (function() {
          var __preExecKeys = Object.keys(this).filter(k => k !== '__preExecKeys');
          var __result = (function() {
            $js
          })();
          // Clean up any new global variables created during execution
          var __postExecKeys = Object.keys(this);
          var __persistentKeys = ${persistentBindings.joinToString(", ", "[", "]") { "\"$it\"" }};
          var __bindingKeys = ${bindings.keys.joinToString(", ", "[", "]") { "\"$it\"" }};
          for (var i = 0; i < __postExecKeys.length; i++) {
            var key = __postExecKeys[i];
            if (__preExecKeys.indexOf(key) === -1 && 
                __persistentKeys.indexOf(key) === -1 && 
                __bindingKeys.indexOf(key) === -1 &&
                !key.startsWith('__')) {
              this[key] = undefined;
            }
          }
          return __result;
        }).call(this)
      """.trimIndent()
      
      val jsSource = Source.newBuilder("js", imports + wrappedScript, "script.js").build()
      val result = jsContext.eval(jsSource)
      
      // Clean up temporary bindings
      bindings.keys.forEach { key -> 
        if (key !in persistentBindings) {
          contextBindings.removeMember(key)
        }
      }
      
      return result
    }

    internal fun evaluateJS(js: String, bindings: Map<String, Any> = emptyMap()): Value {
      val contextBindings = jsContext.getBindings("js")
      bindings.forEach { (key, value) -> contextBindings.putMember(key, value) }
      val jsSource = Source.newBuilder("js", imports + js, "script.js").build()
      return jsContext.eval(jsSource)
    }
  }

  internal fun setRequestAndResponse(pmRequest: Request, httpResponse: org.http4k.core.Response) {
    request = pmRequest
    response =
      Response(httpResponse.status.code, httpResponse.status.toString(), httpResponse.bodyString())
    updateJetbrainsResponse(httpResponse)
  }

  internal fun setRequest(pmRequest: Request) {
    request = pmRequest
  }

  internal fun setResponse(httpResponse: org.http4k.core.Response) {
    response =
      Response(httpResponse.status.code, httpResponse.status.toString(), httpResponse.bodyString())
    updateJetbrainsResponse(httpResponse)
  }

  internal fun setResponse(code: Int, status: String, body: String) {
    response = Response(code, status, body)
    jetbrainsResponse.status = code
    jetbrainsResponse.body = body
  }

  internal fun updateJetbrainsRequest(pmRequest: com.salesforce.revoman.internal.postman.template.Request) {
    jetbrainsRequest.update(pmRequest)
  }

  internal fun updateJetbrainsResponse(httpResponse: org.http4k.core.Response) {
    jetbrainsResponse.update(httpResponse)
  }

  internal fun setTemplateContext(format: TemplateFormat, fileVariables: Map<String, Any?>) {
    templateFormat = format
    jetbrainsFileVariables = fileVariables
  }

  internal fun updateJetbrainsEnvironment(env: Map<String, Any?>) {
    jetbrainsEnvironment = env
  }

  internal fun clearRequestVariables() {
    requestVariables.clear()
  }

  internal fun getRequestVariableAsString(key: String): String? =
    if (!requestVariables.containsKey(key)) {
      null
    } else {
      requestVariables[key]?.let { moshiReVoman.anyToString(it) }
    }

  internal fun resolveJetbrainsVariableAsString(key: String): String? {
    val value =
      resolveVariableInMap(requestVariables, key)
        ?: resolveVariableInMap(environment, key)
        ?: resolveVariableInMap(jetbrainsFileVariables, key)
        ?: resolveVariableInMap(jetbrainsEnvironment, key)
    return value?.let { moshiReVoman.anyToString(it) }
  }

  fun evaluateJS(js: String, bindings: Map<String, Any> = emptyMap()): Value =
    jsEvaluator.evaluateJS(js, bindings)

  /**
   * Evaluates JavaScript in an isolated scope to prevent variable pollution between executions
   */
  fun evaluateJSIsolated(js: String, bindings: Map<String, Any> = emptyMap()): Value =
    jsEvaluator.evaluateJSIsolated(js, bindings)

  /**
   * Resets the JavaScript context by removing all user-defined variables
   * while preserving SDK bindings (pm, xml2Json, client, request, response, $env)
   */
  fun resetJSContext() {
    jsEvaluator.resetContext()
  }

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

  @Suppress("unused")
  inner class JetbrainsClient {
    @JvmField val global: JetbrainsGlobal = JetbrainsGlobal()
    @JvmField val variables: JetbrainsVariables = JetbrainsVariables()

    fun test(testName: String, fn: Value) {
      try {
        fn.executeVoid()
      } catch (error: Throwable) {
        throw RuntimeException("JetBrains test failed: $testName", error)
      }
    }

    fun assert(condition: Boolean, message: String? = null) {
      if (!condition) {
        throw IllegalStateException(message ?: "JetBrains assertion failed")
      }
    }

    fun log(text: Any?) {
      logger.info { "jetbrains-client log: ${pprint(text)}" }
    }

    fun exit() = Unit
  }

  @Suppress("unused")
  inner class JetbrainsGlobal {
    @JvmField val headers: JetbrainsGlobalHeaders = JetbrainsGlobalHeaders()

    fun set(key: String, value: Any?) {
      environment.set(key, value)
    }

    fun get(key: String): Any? = environment[key]

    fun isEmpty(): Boolean = environment.isEmpty()

    fun clear(key: String) {
      environment.unset(key)
    }

    fun clearAll() {
      environment.clear()
    }
  }

  @Suppress("unused")
  inner class JetbrainsGlobalHeaders {
    fun set(key: String, value: String?) {
      if (value == null) {
        globalHeaders.remove(key)
      } else {
        globalHeaders[key] = value
      }
    }

    fun clear(key: String) {
      globalHeaders.remove(key)
    }
  }

  @Suppress("unused")
  inner class JetbrainsRequest {
    @JvmField val variables: JetbrainsRequestVariables = JetbrainsRequestVariables()
    @JvmField val environment: JetbrainsVariableStore = JetbrainsVariableStore { jetbrainsEnvironment }
    @JvmField var method: String = ""
    @JvmField var url: String = ""
    @JvmField var body: String? = null

    fun update(pmRequest: com.salesforce.revoman.internal.postman.template.Request) {
      method = pmRequest.method
      url = pmRequest.url.raw
      body = pmRequest.body?.raw
    }
  }

  @Suppress("unused")
  inner class JetbrainsRequestVariables {
    fun set(key: String, value: Any?) {
      requestVariables[key] = value
    }

    fun get(key: String): Any? = resolveVariableInMap(requestVariables, key)
  }

  @Suppress("unused")
  inner class JetbrainsResponse {
    @JvmField var status: Int = 0
    @JvmField var body: String = ""

    fun update(httpResponse: org.http4k.core.Response) {
      status = httpResponse.status.code
      body = httpResponse.bodyString()
    }
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

  internal fun applyGlobalHeaders(
    request: com.salesforce.revoman.internal.postman.template.Request
  ): com.salesforce.revoman.internal.postman.template.Request {
    if (globalHeaders.isEmpty()) return request
    val existing = request.header.associateBy { it.key.lowercase() }.toMutableMap()
    globalHeaders.forEach { (key, value) ->
      existing[key.lowercase()] = Header(key, value)
    }
    return request.copy(header = existing.values.toList())
  }

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

  @Suppress("unused")
  inner class JetbrainsVariables {
    @JvmField
    val environment: JetbrainsVariableStore = JetbrainsVariableStore { jetbrainsEnvironment }
    @JvmField
    val global: JetbrainsVariableStore = JetbrainsVariableStore { this@PostmanSDK.environment }
    @JvmField
    val file: JetbrainsVariableStore = JetbrainsVariableStore { jetbrainsFileVariables }
    @JvmField
    val request: JetbrainsVariableStore = JetbrainsVariableStore { requestVariables }
  }

  @Suppress("unused")
  inner class JetbrainsVariableStore(private val provider: () -> Map<String, Any?>) {
    fun get(name: String): Any? = resolveVariableInMap(provider(), name)
  }

  private sealed interface JsonPathToken {
    data class Key(val name: String) : JsonPathToken
    data class Index(val index: Int) : JsonPathToken
  }

  private fun resolveVariableInMap(map: Map<String, Any?>, key: String): Any? {
    if (map.containsKey(key)) {
      return map[key]
    }
    val tokens = parseJsonPath(key) ?: return null
    var current: Any? = map
    for (token in tokens) {
      current =
        when {
          token is JsonPathToken.Key && current is Map<*, *> -> current[token.name]
          token is JsonPathToken.Index && current is List<*> -> current.getOrNull(token.index)
          else -> return null
        }
    }
    return current
  }

  private fun parseJsonPath(rawKey: String): List<JsonPathToken>? {
    if (rawKey.isBlank()) return null
    var key = rawKey
    if (key.startsWith("$")) {
      key = key.removePrefix("$")
      if (key.startsWith(".")) {
        key = key.removePrefix(".")
      }
    }
    if (key.isBlank()) return null
    val tokens = mutableListOf<JsonPathToken>()
    val current = StringBuilder()
    var index = 0
    while (index < key.length) {
      val ch = key[index]
      when (ch) {
        '.' -> {
          if (current.isNotEmpty()) {
            tokens.add(JsonPathToken.Key(current.toString()))
            current.setLength(0)
          }
          index++
        }
        '[' -> {
          if (current.isNotEmpty()) {
            tokens.add(JsonPathToken.Key(current.toString()))
            current.setLength(0)
          }
          val (token, nextIndex) = parseBracketToken(key, index)
          token?.let { tokens.add(it) }
          index = nextIndex
        }
        else -> {
          current.append(ch)
          index++
        }
      }
    }
    if (current.isNotEmpty()) {
      tokens.add(JsonPathToken.Key(current.toString()))
    }
    return tokens.ifEmpty { null }
  }

  private fun parseBracketToken(key: String, startIndex: Int): Pair<JsonPathToken?, Int> {
    var index = startIndex + 1
    if (index >= key.length) return null to index
    val quote = key[index]
    if (quote == '\'' || quote == '"') {
      index++
      val tokenStart = index
      while (index < key.length && key[index] != quote) {
        index++
      }
      val content = key.substring(tokenStart, index)
      while (index < key.length && key[index] != ']') {
        index++
      }
      return JsonPathToken.Key(content) to (index + 1)
    }
    val tokenStart = index
    while (index < key.length && key[index] != ']') {
      index++
    }
    val content = key.substring(tokenStart, index).trim()
    val token = content.toIntOrNull()?.let { JsonPathToken.Index(it) }
      ?: if (content.isBlank()) null else JsonPathToken.Key(content)
    return token to (index + 1)
  }
}

data class Info(@JvmField val requestName: String)

private val logger = KotlinLogging.logger {}
