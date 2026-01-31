/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.http

import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.output.postman.PostmanEnvironment
import io.github.oshai.kotlinlogging.KotlinLogging
import org.graalvm.polyglot.Value
import org.http4k.core.Response

internal class JetBrainsClient(
  private val environment: PostmanEnvironment<Any?>,
  private val requestVariables: MutableMap<String, Any?>,
) {
  @JvmField val global = Global(environment)
  @JvmField val variables = Variables(environment, requestVariables)

  fun log(text: Any?) {
    logger.info { "jetbrains-client: ${text?.toString() ?: "null"}" }
  }

  fun assert(condition: Boolean, message: String? = null) {
    if (!condition) {
      throw IllegalStateException(message ?: "JetBrains HTTP Client assertion failed")
    }
  }

  fun test(testName: String, fn: Value) {
    try {
      fn.execute()
    } catch (error: Throwable) {
      throw IllegalStateException("JetBrains HTTP Client test failed: $testName", error)
    }
  }

  @Suppress("unused")
  fun exit() {}

  class Global(private val environment: PostmanEnvironment<Any?>) {
    fun set(name: String, value: Any?) {
      environment.set(name, value)
    }

    fun get(name: String): Any? = environment[name]

    fun clear(name: String) {
      environment.unset(name)
    }

    fun clearAll() {
      environment.clear()
    }
  }

  class Variables(
    private val env: PostmanEnvironment<Any?>,
    private val requestVariables: MutableMap<String, Any?>,
  ) {
    @JvmField val global = Global(env)

    @JvmField val request = RequestVariables(requestVariables)

    @JvmField val environment = EnvironmentVariables(env)
  }

  class RequestVariables(private val requestVariables: MutableMap<String, Any?>) {
    fun set(name: String, value: Any?) {
      requestVariables[name] = value
    }

    fun get(name: String): Any? = requestVariables[name]

    fun clear(name: String) {
      requestVariables.remove(name)
    }
  }

  class EnvironmentVariables(private val environment: PostmanEnvironment<Any?>) {
    fun get(name: String): Any? = environment[name]
  }
}

internal class JetBrainsRequest(
  private val pm: PostmanSDK,
  private val requestVariables: MutableMap<String, Any?>,
) {
  @JvmField val variables = JetBrainsClient.RequestVariables(requestVariables)
  @JvmField val environment = JetBrainsClient.EnvironmentVariables(pm.environment)
  @JvmField val headers = JetBrainsRequestHeaders(pm)
  @JvmField val url = JetBrainsRequestUrl(pm)
  @JvmField val body = JetBrainsRequestBody(pm)

  val method: String
    get() = pm.request.method

  fun url(): String = url.invoke()

  fun body(): String = body.invoke()

  fun iteration(): Int = 0

  fun templateValue(index: Int): Any? = null
}

internal class JetBrainsResponse(private val moshiReVoman: MoshiReVoman) {
  @JvmField var body: Any? = null
  @JvmField var status: Int = 0
  @JvmField var headers: JetBrainsResponseHeaders = JetBrainsResponseHeaders(emptyMap())
  @JvmField var contentType: JetBrainsContentType = JetBrainsContentType("", "")

  fun update(httpResponse: Response) {
    status = httpResponse.status.code
    headers = JetBrainsResponseHeaders.from(httpResponse)
    contentType = JetBrainsContentType.from(httpResponse.header("Content-Type"))
    val responseBody = httpResponse.bodyString()
    body = parseBody(responseBody, contentType.mimeType)
  }

  fun update(code: Int, statusText: String, responseBody: String) {
    status = code
    headers = JetBrainsResponseHeaders(emptyMap())
    contentType = JetBrainsContentType("", "")
    body = parseBody(responseBody, "")
  }

  private fun parseBody(responseBody: String, mimeType: String): Any? {
    val looksJson = mimeType.contains("json", ignoreCase = true) ||
      responseBody.trimStart().startsWith("{") ||
      responseBody.trimStart().startsWith("[")
    if (!looksJson) return responseBody
    return runCatching { moshiReVoman.fromJson<Any>(responseBody) }.getOrDefault(responseBody)
  }
}

internal class JetBrainsResponseHeaders(private val headers: Map<String, List<String>>) {
  fun valueOf(headerName: String): String? =
    headers[headerName.lowercase()]?.firstOrNull()

  fun valuesOf(headerName: String): List<String> =
    headers[headerName.lowercase()].orEmpty()

  companion object {
    fun from(response: Response): JetBrainsResponseHeaders {
      val headerMap = mutableMapOf<String, MutableList<String>>()
      response.headers.forEach { (name, value) ->
        headerMap.getOrPut(name.orEmpty().lowercase()) { mutableListOf() }.add(value.orEmpty())
      }
      return JetBrainsResponseHeaders(headerMap)
    }
  }
}

internal data class JetBrainsContentType(@JvmField val mimeType: String, @JvmField val charset: String) {
  companion object {
    fun from(contentTypeHeader: String?): JetBrainsContentType {
      if (contentTypeHeader.isNullOrBlank()) return JetBrainsContentType("", "")
      val parts = contentTypeHeader.split(";").map { it.trim() }
      val mimeType = parts.firstOrNull().orEmpty()
      val charset =
        parts.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
          ?.substringAfter("=")
          ?.trim()
          .orEmpty()
      return JetBrainsContentType(mimeType, charset)
    }
  }
}

internal class JetBrainsRequestHeaders(private val pm: PostmanSDK) {
  val all: List<JetBrainsRequestHeader>
    get() = pm.request.header.map { JetBrainsRequestHeader(pm, it.key, it.value) }

  fun findByName(name: String): JetBrainsRequestHeader? =
    all.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

internal class JetBrainsRequestHeader(
  private val pm: PostmanSDK,
  @JvmField val name: String,
  private val rawValue: String,
) {
  fun getRawValue(): String = rawValue

  fun tryGetSubstitutedValue(): String =
    pm.regexReplacer.replaceVariablesRecursively(rawValue, pm) ?: rawValue

  fun value(): String = tryGetSubstitutedValue()
}

internal class JetBrainsRequestUrl(private val pm: PostmanSDK) {
  fun getRaw(): String = pm.request.url.raw

  fun tryGetSubstituted(): String =
    pm.regexReplacer.replaceVariablesRecursively(pm.request.url.raw, pm) ?: pm.request.url.raw

  fun invoke(): String = tryGetSubstituted()
}

internal class JetBrainsRequestBody(private val pm: PostmanSDK) {
  fun getRaw(): String = pm.request.body?.raw.orEmpty()

  fun tryGetSubstituted(): String =
    pm.request.body?.raw?.let { pm.regexReplacer.replaceVariablesRecursively(it, pm) } ?: ""

  fun invoke(): String = tryGetSubstituted()
}

private val logger = KotlinLogging.logger {}
