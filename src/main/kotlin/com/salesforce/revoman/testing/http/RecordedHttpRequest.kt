/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.testing.http

import java.nio.charset.Charset
import org.http4k.core.Method

/**
 * An immutable observation of an HTTP request received by a mock handler.
 *
 * Query parameters are decoded by the HTTP adapter and retain repeated entries and their original
 * order. A null query value represents a missing value and remains distinct from a present, empty
 * value. Headers are exposed as adapter-visible name/value pairs, including repeated entries when
 * the adapter reports them; original name casing and global entry order depend on the adapter and
 * transport. The request body is retained independently from the adapter and each [bodyBytes] call
 * returns a fresh copy.
 *
 * Instances are created through a non-public factory so only the mock HTTP adapter can construct
 * snapshots while this observation remains a public value API for handler consumers.
 */
class RecordedHttpRequest
private constructor(
  val method: Method,
  val path: String,
  queryParameters: List<RecordedNameValue>,
  headers: List<RecordedNameValue>,
  body: ByteArray,
) {
  /** Decoded query parameters, preserving repeated entries and adapter order. */
  val queryParameters: List<RecordedNameValue> = java.util.List.copyOf(queryParameters)

  /** Adapter-visible headers; name casing and global entry order are transport-dependent. */
  val headers: List<RecordedNameValue> = java.util.List.copyOf(headers)

  private val bodySnapshot = body.copyOf()

  /** Returns a fresh defensive copy of the recorded request body. */
  fun bodyBytes(): ByteArray = bodySnapshot.copyOf()

  /** Decodes the recorded request body using [charset], defaulting to UTF-8. */
  @JvmOverloads
  fun bodyString(charset: Charset = Charsets.UTF_8): String = String(bodySnapshot, charset)

  internal companion object {
    /** Creates a snapshot for use by the HTTP adapter in this module. */
    @JvmSynthetic
    internal fun create(
      method: Method,
      path: String,
      queryParameters: List<RecordedNameValue>,
      headers: List<RecordedNameValue>,
      body: ByteArray,
    ): RecordedHttpRequest = RecordedHttpRequest(method, path, queryParameters, headers, body)
  }
}
