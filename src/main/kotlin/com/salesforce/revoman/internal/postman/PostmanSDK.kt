/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.github.underscore.U
import com.salesforce.revoman.input.evaluateJS
import com.salesforce.revoman.internal.json.moshiReVoman
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.StepReport
import com.squareup.moshi.adapter
import org.graalvm.polyglot.Value

/**
 * SDK to use in TestsJs, to be compatible with the Postman API reference:
 * https://learning.postman.com/docs/writing-scripts/script-references/postman-sandbox-api-reference/
 */
internal class PostmanSDK(val regexReplacer: RegexReplacer) {
  @JvmField val environment: PostmanEnvironment<Any?> = PostmanEnvironment()
  @JvmField val variables: Variables = Variables()
  lateinit var info: Info
  lateinit var request: Request
  lateinit var response: Response
  lateinit var currentStepReport: StepReport
  lateinit var rundown: Rundown

  @Suppress("unused")
  fun setEnvironmentVariable(key: String, value: String) {
    environment.set(key, value)
  }

  @JvmField val xml2Json = Xml2Json { xml -> jsonStrToObj(U.xmlToJson(xml)) }

  inner class Variables {
    fun has(variableKey: String) = environment.containsKey(variableKey)

    fun get(variableKey: String) = environment[variableKey]

    fun set(variableKey: String, value: String) {
      environment.set(variableKey, value)
    }

    fun replaceIn(stringToReplace: String): String =
      regexReplacer.replaceVariablesRecursively(stringToReplace, currentStepReport, rundown) ?: ""
  }
}

@SuppressWarnings("kotlin:S6517")
@FunctionalInterface // DON'T REMOVE THIS. Polyglot won't work without this
internal fun interface Xml2Json {
  @Suppress("unused") fun xml2Json(xml: String): Any?
}

/**
 * https://www.graalvm.org/22.3/reference-manual/embed-languages/#define-guest-language-functions-as-java-values
 */
data class Response(val code: Int, val status: String, val body: String) {
  fun json(): Value? = evaluateJS("jsonStr => JSON.parse(jsonStr)")?.execute(body)
}

data class Info(val requestName: String)

@OptIn(ExperimentalStdlibApi::class)
fun jsonStrToObj(jsonStr: String): Any? = moshiReVoman.adapter<Any>().fromJson(jsonStr)

// * NOTE 10/09/23 gopala.akshintala: This needs to be Global singleton for Graal JS to work
internal lateinit var pm: PostmanSDK
