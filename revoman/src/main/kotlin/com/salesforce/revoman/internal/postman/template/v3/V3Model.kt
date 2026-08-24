/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

internal data class V3CollectionDef(
  val kind: String = "collection",
  val order: Int? = null,
  val auth: List<V3Auth> = emptyList(),
)

internal data class V3Request(
  val kind: String = "http-request",
  val name: String? = null,
  val description: String? = null,
  val url: String,
  val method: String,
  val headers: Map<String, String> = emptyMap(),
  val queryParams: Map<String, String> = emptyMap(),
  val body: V3Body? = null,
  val scripts: List<V3Script> = emptyList(),
  val auth: List<V3Auth> = emptyList(),
  val settings: V3Settings? = null,
  val order: Int? = null,
)

internal data class V3Body(val type: String, val content: String)

internal data class V3Script(val type: String, val code: String, val language: String? = null)

internal data class V3Auth(
  val id: String? = null,
  val type: String,
  val name: String? = null,
  val credentials: Map<String, String> = emptyMap(),
)

internal data class V3Settings(val disabledSystemHeaders: List<String> = emptyList())

internal data class V3Env(val name: String?, val values: List<V3EnvValue> = emptyList())

internal data class V3EnvValue(val key: String, val value: String?)
