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

  @Test
  fun testConvertJsonBodyToRawMode() {
    val v3 =
      V3Request(
        url = "{{baseUrl}}/x",
        method = "POST",
        body = V3Body(type = "json", content = """{"a":1}"""),
      )
    val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
    assertThat(item.request.body).isNotNull()
    assertThat(item.request.body!!.mode).isEqualTo("raw")
    assertThat(item.request.body!!.raw).isEqualTo("""{"a":1}""")
  }

  @Test
  fun testConvertTextBodyToRawMode() {
    val v3 =
      V3Request(
        url = "{{baseUrl}}/x",
        method = "POST",
        body = V3Body(type = "text", content = "<xml/>"),
      )
    val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
    assertThat(item.request.body!!.mode).isEqualTo("raw")
    assertThat(item.request.body!!.raw).isEqualTo("<xml/>")
  }

  @Test
  fun testAfterResponseScriptMapsToTestEvent() {
    val v3 =
      V3Request(
        url = "{{baseUrl}}/x",
        method = "GET",
        scripts = listOf(V3Script(type = "afterResponse", code = "console.log(1)\nconsole.log(2)")),
      )
    val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
    assertThat(item.event).isNotNull()
    assertThat(item.event).hasSize(1)
    val event = item.event!!.single()
    assertThat(event.listen).isEqualTo("test")
    assertThat(event.script.exec).containsExactly("console.log(1)", "console.log(2)").inOrder()
  }

  @Test
  fun testBeforeRequestScriptMapsToPrerequestEvent() {
    val v3 =
      V3Request(
        url = "{{baseUrl}}/x",
        method = "GET",
        scripts = listOf(V3Script(type = "beforeRequest", code = "var x = 1")),
      )
    val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
    val event = item.event!!.single()
    assertThat(event.listen).isEqualTo("prerequest")
  }

  @Test
  fun testMultipleScriptsOfSameTypeAreMergedIntoOneEvent() {
    val v3 =
      V3Request(
        url = "{{baseUrl}}/x",
        method = "GET",
        scripts =
          listOf(
            V3Script(type = "afterResponse", code = "a()"),
            V3Script(type = "afterResponse", code = "b()"),
          ),
      )
    val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
    assertThat(item.event).hasSize(1)
    assertThat(item.event!!.single().script.exec).containsExactly("a()", "b()").inOrder()
  }

  @Test
  fun testUnknownScriptTypeIsSkipped() {
    val v3 =
      V3Request(
        url = "{{baseUrl}}/x",
        method = "GET",
        scripts = listOf(V3Script(type = "unknown", code = "x")),
      )
    val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
    assertThat(item.event).isNull()
  }

  @Test
  fun testConvertBearerAuthFromV3List() {
    val authList =
      listOf(V3Auth(type = "bearer", name = "bearer auth", credentials = mapOf("token" to "abc")))
    val auth = V3ToV2Converter.toAuth(authList)!!
    assertThat(auth.type).isEqualTo("bearer")
    assertThat(auth.bearer).hasSize(1)
    val b = auth.bearer.single()
    assertThat(b.key).isEqualTo("bearer auth")
    assertThat(b.type).isEqualTo("bearer")
    assertThat(b.value).isEqualTo("abc")
  }

  @Test
  fun testNonBearerAuthIsDropped() {
    val authList =
      listOf(V3Auth(type = "basic", credentials = mapOf("username" to "u", "password" to "p")))
    val auth = V3ToV2Converter.toAuth(authList)
    assertThat(auth).isNull()
  }

  @Test
  fun testRequestAuthOverridesInheritedAuth() {
    val inherited =
      V3ToV2Converter.toAuth(
        listOf(V3Auth(type = "bearer", credentials = mapOf("token" to "INHERITED")))
      )
    val v3 =
      V3Request(
        url = "{{baseUrl}}/x",
        method = "GET",
        auth = listOf(V3Auth(type = "bearer", credentials = mapOf("token" to "OVERRIDDEN"))),
      )
    val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = inherited)
    assertThat(item.request.auth!!.bearer.single().value).isEqualTo("OVERRIDDEN")
  }

  @Test
  fun testInheritedAuthAppliesWhenRequestHasNoAuth() {
    val inherited =
      V3ToV2Converter.toAuth(
        listOf(V3Auth(type = "bearer", credentials = mapOf("token" to "INHERITED")))
      )
    val v3 = V3Request(url = "{{baseUrl}}/x", method = "GET")
    val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = inherited)
    assertThat(item.request.auth!!.bearer.single().value).isEqualTo("INHERITED")
  }

  @Test
  fun `query param value with space is percent-encoded`() {
    val merged = V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x", mapOf("q" to "hello world"))
    assertThat(merged).isEqualTo("{{baseUrl}}/x?q=hello%20world")
  }

  @Test
  fun `query param value with ampersand and equals is percent-encoded`() {
    val merged = V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x", mapOf("q" to "A&B=C"))
    assertThat(merged).isEqualTo("{{baseUrl}}/x?q=A%26B%3DC")
  }

  @Test
  fun `query param value with plus sign is percent-encoded`() {
    val merged = V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x", mapOf("q" to "A+B"))
    assertThat(merged).isEqualTo("{{baseUrl}}/x?q=A%2BB")
  }

  @Test
  fun `query param value with single quote is percent-encoded`() {
    val merged = V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x", mapOf("q" to "A'B"))
    assertThat(merged).isEqualTo("{{baseUrl}}/x?q=A%27B")
  }

  @Test
  fun `query param value containing Postman variable placeholder survives unencoded`() {
    val merged = V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x", mapOf("q" to "{{var}}"))
    assertThat(merged).isEqualTo("{{baseUrl}}/x?q={{var}}")
  }

  @Test
  fun `query param value with placeholder and special chars encodes only the special chars`() {
    val merged =
      V3ToV2Converter.mergeQueryParams(
        "{{baseUrl}}/x",
        mapOf("q" to "SELECT Id FROM Account WHERE Name='{{name}}'"),
      )
    assertThat(merged)
      .isEqualTo("{{baseUrl}}/x?q=SELECT%20Id%20FROM%20Account%20WHERE%20Name%3D%27{{name}}%27")
  }

  @Test
  fun `query param key is also percent-encoded`() {
    val merged = V3ToV2Converter.mergeQueryParams("{{baseUrl}}/x", mapOf("key with space" to "val"))
    assertThat(merged).isEqualTo("{{baseUrl}}/x?key%20with%20space=val")
  }

  @Test
  fun `unreserved chars in query params are not encoded`() {
    val merged =
      V3ToV2Converter.mergeQueryParams(
        "{{baseUrl}}/x",
        mapOf("k" to "azAZ09-._~"),
      )
    assertThat(merged).isEqualTo("{{baseUrl}}/x?k=azAZ09-._~")
  }
}
