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

class JetBrainsHttpTemplateProviderTest {
  @Test
  fun `parses inline variables and scripts`() {
    val content =
      """
      @baseUrl = https://example.com

      ### sample
      < {%
      client.global.set("limit", 1);
      %}
      GET {{baseUrl}}/pokemon

      > {%
      client.global.set("id", 1);
      %}
      """
        .trimIndent()
    val provider = JetBrainsHttpTemplateProvider()
    val result =
      provider.load(
        TemplateSource(
          name = "sample.http",
          content = content,
          extension = "http",
        )
      )

    result.fileVariables["baseUrl"] shouldBe "https://example.com"
    result.steps shouldHaveSize 1
    val step = result.steps.first()
    step.rawPMStep.name shouldBe "sample"
    step.rawPMStep.request.method shouldBe "GET"
    step.rawPMStep.request.url.raw shouldBe "{{baseUrl}}/pokemon"
    step.rawPMStep.event!!.map { it.listen } shouldBe listOf("prerequest", "test")
  }
}
