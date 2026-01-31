/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.http

import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.internal.postman.RegexReplacer
import com.salesforce.revoman.internal.postman.template.Header
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.output.report.StepReport
import io.github.oshai.kotlinlogging.KotlinLogging
import org.graalvm.polyglot.Value
import org.http4k.lens.contentType

internal class JetBrainsHttpRuntime(
  private val pm: PostmanSDK,
  private val regexReplacer: RegexReplacer,
) {
  private val requestVariableOriginals: MutableMap<String, Any?> = mutableMapOf()
  private val requestVariables: MutableMap<String, Any?> = mutableMapOf()
  private val globalHeaders: LinkedHashMap<String, String> = linkedMapOf()
  private val client = Client(pm, globalHeaders)
  private var currentRequest: Request? = null

  fun beginStep(request: Request) {
    currentRequest = request
    requestVariableOriginals.clear()
    requestVariables.clear()
  }

  fun restoreRequestVariables() {
    requestVariableOriginals.forEach { (key, previous) ->
      when (previous) {
        MissingValue -> pm.environment.remove(key)
        else -> pm.environment[key] = previous
      }
    }
    requestVariableOriginals.clear()
    requestVariables.clear()
  }

  fun applyGlobalHeaders(item: Item): Item {
    if (globalHeaders.isEmpty()) {
      return item
    }
    val existingHeaders = item.request.header
    val existingKeys = existingHeaders.map { it.key.lowercase() }.toSet()
    val merged =
      globalHeaders
        .filter { it.value.isNotBlank() && !existingKeys.contains(it.key.lowercase()) }
        .map { Header(it.key, it.value) } + existingHeaders
    return item.copy(request = item.request.copy(header = merged))
  }

  fun executePreRequestScript(script: String) {
    val requestBinding = RequestBinding(currentRequest, pm, regexReplacer, this)
    executeScript(script, mapOf("client" to client, "request" to requestBinding))
  }

  fun executeResponseHandlerScript(script: String, stepReport: StepReport) {
    val httpResponse = stepReport.responseInfo!!.get().httpMsg
    val responseBinding = ResponseBinding(httpResponse)
    val requestBinding = RequestBinding(currentRequest, pm, regexReplacer, this)
    executeScript(
      script,
      mapOf("client" to client, "request" to requestBinding, "response" to responseBinding),
    )
  }

  internal fun setRequestVariable(key: String, value: Any?) {
    if (!requestVariableOriginals.containsKey(key)) {
      requestVariableOriginals[key] =
        if (pm.environment.containsKey(key)) pm.environment[key] else MissingValue
    }
    requestVariables[key] = value
    pm.environment[key] = value
    logger.info { "HTTP request variable set: $key=$value" }
  }

  internal fun getRequestVariable(key: String): Any? =
    if (requestVariables.containsKey(key)) requestVariables[key] else pm.environment[key]

  internal fun clearRequestVariable(key: String) {
    setRequestVariable(key, null)
  }

  private fun executeScript(script: String, bindings: Map<String, Any>) {
    runCatching { 
      pm.evaluateJSIsolated(script, bindings) // Use isolated evaluation to prevent variable pollution
    }
      .onFailure { failure ->
        if (failure is ClientExit) {
          return
        }
        throw failure
      }
  }

  internal class Client(
    private val pm: PostmanSDK,
    private val globalHeaders: LinkedHashMap<String, String>,
  ) {
    @JvmField @Suppress("unused") val global: Global = Global(pm, globalHeaders)

    @Suppress("unused")
    fun log(text: Any?) {
      logger.info { text?.toString().orEmpty() }
    }

    @Suppress("unused")
    fun assert(condition: Boolean, message: String? = null) {
      if (!condition) {
        throw AssertionError(message ?: "Assertion failed")
      }
    }

    @Suppress("unused")
    fun test(testName: String, fn: Value) {
      logger.info { "Executing HTTP client test: $testName" }
      fn.execute()
    }

    @Suppress("unused")
    fun exit() {
      throw ClientExit()
    }
  }

  internal class Global(
    private val pm: PostmanSDK,
    private val globalHeaders: LinkedHashMap<String, String>,
  ) {
    @Suppress("unused") val headers: Headers = Headers(globalHeaders)

    @Suppress("unused")
    fun set(varName: String, varValue: Any?) {
      pm.environment.set(varName, varValue)
    }

    @Suppress("unused")
    fun get(varName: String): Any? = pm.environment[varName]

    @Suppress("unused")
    fun isEmpty(): Boolean = pm.environment.isEmpty()

    @Suppress("unused")
    fun clear(varName: String) {
      pm.environment.remove(varName)
    }

    @Suppress("unused")
    fun clearAll() {
      pm.environment.clear()
    }
  }

  internal class Headers(private val globalHeaders: LinkedHashMap<String, String>) {
    @Suppress("unused")
    fun set(headerName: String, headerValue: String?) {
      if (headerValue == null) {
        globalHeaders.remove(headerName)
      } else {
        globalHeaders[headerName] = headerValue
      }
    }

    @Suppress("unused")
    fun clear(headerName: String) {
      globalHeaders.remove(headerName)
    }
  }

  internal class RequestBinding(
    private val request: Request?,
    private val pm: PostmanSDK,
    private val regexReplacer: RegexReplacer,
    private val runtime: JetBrainsHttpRuntime,
  ) {
    @JvmField @Suppress("unused") val variables: RequestVariables = RequestVariables(runtime)
    @JvmField @Suppress("unused") val method: String = request?.method.orEmpty()
    @JvmField
    @Suppress("unused")
    val url: RawAndSubstituted = RawAndSubstituted(request?.url?.raw.orEmpty(), pm, regexReplacer)
    @JvmField
    @Suppress("unused")
    val body: RawAndSubstituted = RawAndSubstituted(request?.body?.raw.orEmpty(), pm, regexReplacer)
    @JvmField
    @Suppress("unused")
    val headers: RequestHeaders = RequestHeaders(request?.header.orEmpty(), pm, regexReplacer)

    @Suppress("unused")
    fun url(): String = url.tryGetSubstituted()

    @Suppress("unused")
    fun body(): String = body.tryGetSubstituted()
  }

  internal class RequestVariables(private val runtime: JetBrainsHttpRuntime) {
    @Suppress("unused")
    fun set(name: String, value: Any?) {
      runtime.setRequestVariable(name, value)
    }

    @Suppress("unused")
    fun get(name: String): Any? = runtime.getRequestVariable(name)

    @Suppress("unused")
    fun clear(name: String) {
      runtime.clearRequestVariable(name)
    }
  }

  internal class RawAndSubstituted(
    private val raw: String,
    private val pm: PostmanSDK,
    private val regexReplacer: RegexReplacer,
  ) {
    @Suppress("unused") fun getRaw(): String = raw

    @Suppress("unused")
    fun tryGetSubstituted(): String =
      regexReplacer.replaceVariablesRecursively(raw, pm) ?: raw
  }

  internal class RequestHeaders(
    private val headers: List<Header>,
    private val pm: PostmanSDK,
    private val regexReplacer: RegexReplacer,
  ) {
    @Suppress("unused")
    fun all(): List<RequestHeader> = headers.map { RequestHeader(it, pm, regexReplacer) }

    @Suppress("unused")
    fun findByName(name: String): RequestHeader? =
      headers.firstOrNull { it.key.equals(name, ignoreCase = true) }?.let {
        RequestHeader(it, pm, regexReplacer)
      }
  }

  internal class RequestHeader(
    private val header: Header,
    private val pm: PostmanSDK,
    private val regexReplacer: RegexReplacer,
  ) {
    @JvmField @Suppress("unused") val name: String = header.key

    @Suppress("unused") fun getRawValue(): String = header.value

    @Suppress("unused")
    fun tryGetSubstitutedValue(): String =
      regexReplacer.replaceVariablesRecursively(header.value, pm) ?: header.value
  }

  internal class ResponseBinding(private val response: org.http4k.core.Response) {
    @JvmField @Suppress("unused") val status: Int = response.status.code
    @JvmField @Suppress("unused") val body: String = response.bodyString()
    @JvmField @Suppress("unused") val headers: ResponseHeaders = ResponseHeaders(response)
    @JvmField @Suppress("unused") val contentType: ContentTypeInfo? =
      response.contentType()?.let { ContentTypeInfo(it.value, null) }
  }

  internal class ResponseHeaders(private val response: org.http4k.core.Response) {
    @Suppress("unused")
    fun valueOf(headerName: String): String? = response.header(headerName)

    @Suppress("unused")
    fun valuesOf(headerName: String): List<String?> =
      response.headers.filter { it.first.equals(headerName, true) }.map { it.second }
  }

  internal data class ContentTypeInfo(@Suppress("unused") val mimeType: String, @Suppress("unused") val charset: String?)

  private object MissingValue

  private class ClientExit : RuntimeException()
}

private val logger = KotlinLogging.logger {}
