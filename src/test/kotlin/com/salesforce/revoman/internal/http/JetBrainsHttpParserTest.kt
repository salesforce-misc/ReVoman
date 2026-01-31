/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.http

import com.salesforce.revoman.internal.template.TemplateSource
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JetBrainsHttpParserTest {
  @Test
  fun `parses requests with scripts and headers`() {
    val content =
      """
      @token = abc123
      ### get-pokemon
      GET https://example.com/pokemon
      X-Test: {{value}}

      > {%
      client.log("ok");
      %}

      # @name = create-pokemon
      < {%
      request.variables.set("id", "42");
      %}
      POST https://example.com/pokemon/{{id}}
      Content-Type: application/json

      {"id":"{{id}}"}
      """
        .trimIndent()
    val source = TemplateSource(name = "sample.http", content = content, extension = "http")
    val parseResult = JetBrainsHttpParser.parse(source)
    val requests = parseResult.requests

    requests shouldHaveSize 2
    parseResult.fileVariables["token"] shouldBe "abc123"
    requests[0].name shouldBe "get-pokemon"
    requests[0].method shouldBe "GET"
    requests[0].headers.first().key shouldBe "X-Test"
    requests[0].responseHandlerScript?.trim() shouldBe """client.log("ok");"""

    requests[1].name shouldBe "create-pokemon"
    requests[1].method shouldBe "POST"
    requests[1].preRequestScript?.contains("request.variables.set") shouldBe true
    requests[1].body?.trim() shouldBe """{"id":"{{id}}"}"""
  }
}
