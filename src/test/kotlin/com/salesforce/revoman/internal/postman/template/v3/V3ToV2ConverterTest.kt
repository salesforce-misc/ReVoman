/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class V3ToV2ConverterTest {
  @Test
  fun testConvertBasicGetRequestUsesFilenameWhenNameAbsent() {
    val v3 = V3Request(url = "{{baseUrl}}/x", method = "GET")
    val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
    assertThat(item.name).isEqualTo("x")
    assertThat(item.request.method).isEqualTo("GET")
    assertThat(item.request.url.raw).isEqualTo("{{baseUrl}}/x")
    assertThat(item.request.header).isEmpty()
    assertThat(item.request.body).isNull()
    assertThat(item.request.auth).isNull()
    assertThat(item.event).isNull()
  }

  @Test
  fun testConvertRequestNameOverridesFilename() {
    val v3 = V3Request(name = "explicit-name", url = "{{baseUrl}}/x", method = "GET")
    val item = V3ToV2Converter.toItem(v3, fallbackName = "filename-fallback", inheritedAuth = null)
    assertThat(item.name).isEqualTo("explicit-name")
  }

  @Test
  fun testConvertHeadersPreserveInsertionOrder() {
    val v3 =
      V3Request(
        url = "{{baseUrl}}/x",
        method = "GET",
        headers = linkedMapOf("a" to "1", "b" to "2", "c" to "3"),
      )
    val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
    val keys = item.request.header.map { it.key }
    assertThat(keys).containsExactly("a", "b", "c").inOrder()
  }

  @Test
  fun testQueryParamsAppendedToUrlWhenUrlHasNoQueryString() {
    val merged =
      V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x", linkedMapOf("foo" to "1", "bar" to "2"))
    assertThat(merged).isEqualTo("{{baseUrl}}/x?foo=1&bar=2")
  }

  @Test
  fun testQueryParamsAppendedAfterExistingQueryString() {
    val merged =
      V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x?already=here", linkedMapOf("foo" to "1"))
    assertThat(merged).isEqualTo("{{baseUrl}}/x?already=here&foo=1")
  }

  @Test
  fun testQueryParamsDuplicateKeyPreservedPerHttpSpec() {
    val merged = V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x?foo=1", linkedMapOf("foo" to "2"))
    assertThat(merged).isEqualTo("{{baseUrl}}/x?foo=1&foo=2")
  }

  @Test
  fun testEmptyQueryParamsLeavesUrlUntouched() {
    val merged = V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x", emptyMap())
    assertThat(merged).isEqualTo("{{baseUrl}}/x")
  }
}
