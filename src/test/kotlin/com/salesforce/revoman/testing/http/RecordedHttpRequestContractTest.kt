/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.testing.http

import com.google.common.truth.Truth.assertThat
import org.http4k.core.Method
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RecordedHttpRequestContractTest {
  @Test
  fun `recorded values and body remain immutable`() {
    val query = mutableListOf(RecordedNameValue("tag", "first"))
    val headers = mutableListOf(RecordedNameValue("X-Repeat", "one"))
    val sourceBody = "café".toByteArray(Charsets.UTF_8)
    val request = RecordedHttpRequest.create(Method.POST, "/items", query, headers, sourceBody)

    query += RecordedNameValue("tag", "second")
    headers.clear()
    sourceBody.fill(0)
    val firstCopy = request.bodyBytes()
    firstCopy.fill(0)

    assertThat(request.method).isEqualTo(Method.POST)
    assertThat(request.path).isEqualTo("/items")
    assertThat(request.queryParameters).containsExactly(RecordedNameValue("tag", "first"))
    assertThat(request.headers).containsExactly(RecordedNameValue("X-Repeat", "one"))
    assertThat(request.bodyString()).isEqualTo("café")
    assertThat(request.bodyBytes()).isEqualTo("café".toByteArray(Charsets.UTF_8))
    assertThrows<UnsupportedOperationException> {
      (request.queryParameters as MutableList).add(RecordedNameValue("x", "y"))
    }
  }

  @Test
  fun `body string accepts an explicit charset`() {
    val request =
      RecordedHttpRequest.create(
        Method.GET,
        "/encoded",
        emptyList(),
        emptyList(),
        "snowman ☃".toByteArray(Charsets.UTF_16LE),
      )

    assertThat(request.bodyString(Charsets.UTF_16LE)).isEqualTo("snowman ☃")
  }
}
