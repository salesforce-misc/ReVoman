/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.github.underscore.U
import com.salesforce.revoman.internal.exe.jsContext
import com.salesforce.revoman.internal.json.moshiReVoman
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.squareup.moshi.adapter
import org.graalvm.polyglot.Value

/**
 * SDK to use in TestsJs, to be compatible with the Postman API reference:
 * https://learning.postman.com/docs/writing-scripts/script-references/postman-sandbox-api-reference/
 */
internal class PostmanSDK {
  @JvmField val environment: PostmanEnvironment<Any?> = PostmanEnvironment()
  lateinit var request: Request
  lateinit var response: Response

  @Suppress("unused")
  fun setEnvironmentVariable(key: String, value: String) {
    environment.set(key, value)
  }

  @JvmField val xml2Json = Xml2Json { xml -> jsonStrToObj(U.xmlToJson(xml)) }
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
  fun json(): Value? = jsContext.eval("js", "jsonStr => JSON.parse(jsonStr)").execute(body)
}

@OptIn(ExperimentalStdlibApi::class)
fun jsonStrToObj(jsonStr: String): Any? = moshiReVoman.adapter<Any>().fromJson(jsonStr)
