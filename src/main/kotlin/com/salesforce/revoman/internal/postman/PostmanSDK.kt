/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ****************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.github.underscore.U
import com.salesforce.revoman.internal.postman.state.Request
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter

/**
 * SDK to be used in Javascript as per this API reference:
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

  @OptIn(ExperimentalStdlibApi::class)
  @JvmField
  val xml2Json = Xml2Json { xml ->
    Moshi.Builder().build().adapter<Map<*, *>>().fromJson(U.xmlToJson(xml))
  }
}

@SuppressWarnings("kotlin:S6517")
@FunctionalInterface // DON'T REMOVE THIS. Polyglot won't work without this
internal fun interface Xml2Json {
  @Suppress("unused") fun xml2Json(xml: String): Map<*, *>?
}

@JsonClass(generateAdapter = true)
data class Response(val code: String, val status: String, val body: String)
