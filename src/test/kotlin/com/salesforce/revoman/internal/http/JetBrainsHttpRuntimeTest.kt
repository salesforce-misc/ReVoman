/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.http

import arrow.core.Either.Right
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.internal.postman.RegexReplacer
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.internal.template.TemplateType
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxnInfo
import io.kotest.matchers.shouldBe
import org.http4k.core.Response
import org.http4k.core.Status
import org.junit.jupiter.api.Test

class JetBrainsHttpRuntimeTest {
  private val moshi = initMoshi()
  private val regexReplacer = RegexReplacer()
  private val pm = PostmanSDK(moshi)
  private val runtime = JetBrainsHttpRuntime(pm, regexReplacer)

  @Test
  fun `pre-request variables are applied during replacement`() {
    val request = Request(method = "GET", url = Url("https://example.com/{{id}}"))
    val item = Item(name = "req", request = request)
    val step = Step("1", item, templateType = TemplateType.JETBRAINS_HTTP)

    pm.environment.currentStep = step
    runtime.beginStep(request)
    runtime.executePreRequestScript("request.variables.set('id', '42');")

    val replaced = regexReplacer.replaceVariablesInRequestRecursively(request, pm)
    replaced.url.raw shouldBe "https://example.com/42"
    runtime.restoreRequestVariables()
  }

  @Test
  fun `response handler updates global environment`() {
    val request = Request(method = "GET", url = Url("https://example.com"))
    val item = Item(name = "req", request = request)
    val step = Step("1", item, templateType = TemplateType.JETBRAINS_HTTP)
    pm.environment.currentStep = step
    runtime.beginStep(request)

    val httpResponse = Response(Status.OK).body("{\"ok\":true}")
    val requestInfo = Right(TxnInfo(httpMsg = request.toHttpRequestSafe(moshi), moshiReVoman = moshi))
    val responseInfo = Right(TxnInfo(httpMsg = httpResponse, moshiReVoman = moshi))
    val stepReport = StepReport(step = step, requestInfo = requestInfo, responseInfo = responseInfo, pmEnvSnapshot = pm.environment)
    pm.currentStepReport = stepReport

    runtime.executeResponseHandlerScript("client.global.set('token', 'abc');", stepReport)
    pm.environment["token"] shouldBe "abc"
    runtime.restoreRequestVariables()
  }
}
