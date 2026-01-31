/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.template

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JetbrainsHttpTemplateProviderTest {
  @Test
  fun `parses http file with scripts`() {
    val source = TemplateSource.fromPath("http-templates/sample.http")
    val provider = JetbrainsHttpTemplateProvider()

    val result = provider.parse(source)

    result.format shouldBe TemplateFormat.JETBRAINS_HTTP
    result.steps.size shouldBe 2
    result.fileVariables["baseUrl"] shouldBe "https://example.org"
    val firstStep = result.steps.first().rawPMStep
    firstStep.name shouldBe "Get Pokemon"
    firstStep.request.method shouldBe "GET"
    firstStep.request.url.raw shouldBe "{{baseUrl}}/pokemon?limit={{limit}}"
    firstStep.request.header.map { it.key } shouldContain "X-Test"
    val listeners = firstStep.event.orEmpty().map { it.listen }
    listeners shouldContain "prerequest"
    listeners shouldContain "test"
  }
}
