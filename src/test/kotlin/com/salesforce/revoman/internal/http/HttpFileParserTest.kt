/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.http

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HttpFileParserTest {

  @Test
  fun `parse single GET request`() {
    val content =
      """
      ### my-request
      GET https://api.example.com/users
      """
        .trimIndent()

    val items = HttpFileParser.parse(content)
    items shouldHaveSize 1
    items[0].name shouldBe "my-request"
    items[0].request.method shouldBe "GET"
    items[0].request.url.raw shouldBe "https://api.example.com/users"
    items[0].request.body.shouldBeNull()
    items[0].event.shouldBeNull()
  }

  @Test
  fun `parse POST request with headers and body`() {
    val content =
      """
      ### create-user
      POST https://api.example.com/users
      Content-Type: application/json
      Authorization: Bearer token123

      {
        "name": "John",
        "email": "john@example.com"
      }
      """
        .trimIndent()

    val items = HttpFileParser.parse(content)
    items shouldHaveSize 1
    items[0].name shouldBe "create-user"
    items[0].request.method shouldBe "POST"
    items[0].request.header shouldHaveSize 2
    items[0].request.header[0].key shouldBe "Content-Type"
    items[0].request.header[0].value shouldBe "application/json"
    items[0].request.header[1].key shouldBe "Authorization"
    items[0].request.header[1].value shouldBe "Bearer token123"
    items[0].request.body.shouldNotBeNull()
    items[0].request.body!!.raw shouldBe
      """
      {
        "name": "John",
        "email": "john@example.com"
      }
      """
        .trimIndent()
  }

  @Test
  fun `parse multiple requests separated by ###`() {
    val content =
      """
      ### first
      GET https://api.example.com/one

      ### second
      GET https://api.example.com/two

      ### third
      POST https://api.example.com/three
      """
        .trimIndent()

    val items = HttpFileParser.parse(content)
    items shouldHaveSize 3
    items[0].name shouldBe "first"
    items[1].name shouldBe "second"
    items[2].name shouldBe "third"
    items[2].request.method shouldBe "POST"
  }

  @Test
  fun `parse response handler`() {
    val content =
      """
      ### test-handler
      GET https://api.example.com/data

      > {%
          var data = JSON.parse(response.body);
          client.global.set("key", data.value);
      %}
      """
        .trimIndent()

    val items = HttpFileParser.parse(content)
    items shouldHaveSize 1
    items[0].event.shouldNotBeNull()
    val testEvent = items[0].event!!.find { it.listen == "test" }
    testEvent.shouldNotBeNull()
    testEvent.script.exec shouldHaveSize 2
    testEvent.script.exec[0].trim() shouldBe "var data = JSON.parse(response.body);"
    testEvent.script.exec[1].trim() shouldBe """client.global.set("key", data.value);"""
  }

  @Test
  fun `parse pre-request handler`() {
    val content =
      """
      ### test-prereq
      < {%
          request.variables.set("token", "abc");
      %}
      GET https://api.example.com/data
      Authorization: Bearer {{token}}
      """
        .trimIndent()

    val items = HttpFileParser.parse(content)
    items shouldHaveSize 1
    items[0].event.shouldNotBeNull()
    val preReqEvent = items[0].event!!.find { it.listen == "prerequest" }
    preReqEvent.shouldNotBeNull()
    preReqEvent.script.exec shouldHaveSize 1
  }

  @Test
  fun `parse request with variables`() {
    val content =
      """
      ### get-pokemon
      GET {{baseUrl}}/pokemon?offset={{offset}}&limit={{limit}}
      """
        .trimIndent()

    val items = HttpFileParser.parse(content)
    items shouldHaveSize 1
    items[0].request.url.raw shouldBe "{{baseUrl}}/pokemon?offset={{offset}}&limit={{limit}}"
  }

  @Test
  fun `ignore comments`() {
    val content =
      """
      # This is a comment
      // This is also a comment
      ### my-request
      GET https://api.example.com/users
      """
        .trimIndent()

    val items = HttpFileParser.parse(content)
    items shouldHaveSize 1
    items[0].name shouldBe "my-request"
  }

  @Test
  fun `auto-generate name when missing`() {
    val content =
      """
      ###
      GET https://api.example.com/users
      """
        .trimIndent()

    val items = HttpFileParser.parse(content)
    items shouldHaveSize 1
    items[0].name shouldBe "Request 1"
  }

  @Test
  fun `handle empty blocks gracefully`() {
    val content =
      """
      ###

      ### real-request
      GET https://api.example.com/users
      """
        .trimIndent()

    val items = HttpFileParser.parse(content)
    items shouldHaveSize 1
    items[0].name shouldBe "real-request"
  }

  @Test
  fun `parse request without separator at start`() {
    val content = "GET https://api.example.com/users"

    val items = HttpFileParser.parse(content)
    items shouldHaveSize 1
    items[0].name shouldBe "Request 1"
    items[0].request.method shouldBe "GET"
  }

  @Test
  fun `parse name from @name tag`() {
    val content =
      """
      ###
      // @name my-named-request
      GET https://api.example.com/users
      """
        .trimIndent()

    val items = HttpFileParser.parse(content)
    items shouldHaveSize 1
    items[0].name shouldBe "my-named-request"
  }

  @Test
  fun `parse response handler and body together`() {
    val content =
      """
      ### post-with-handler
      POST https://api.example.com/data
      Content-Type: application/json

      {"key": "value"}

      > {%
          client.global.set("result", response.body);
      %}
      """
        .trimIndent()

    val items = HttpFileParser.parse(content)
    items shouldHaveSize 1
    items[0].request.body.shouldNotBeNull()
    items[0].request.body!!.raw shouldBe """{"key": "value"}"""
    items[0].event.shouldNotBeNull()
    items[0].event!!.find { it.listen == "test" }.shouldNotBeNull()
  }
}
