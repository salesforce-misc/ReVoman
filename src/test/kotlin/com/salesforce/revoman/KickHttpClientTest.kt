/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.report.failure.RequestFailure.HttpRequestFailure
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class KickHttpClientTest {

  private val whisperBase = "http://whisper.invalid"

  @Test
  fun `custom httpClient is invoked and no listen port is required`() {
    val uris = mutableListOf<String>()
    val handler: HttpHandler = { request ->
      uris += request.uri.toString()
      Response(OK).body("""{"ok":true}""")
    }
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v3/single-ok")
          .dynamicEnvironment("baseUrl", whisperBase)
          .httpClient(handler)
          .off()
      )
    val report = rundown.reportForStepName("o")!!
    assertThat(report.isSuccessful).isTrue()
    assertThat(uris).containsExactly("$whisperBase/ok")
    assertThat(report.responseInfo!!.get().httpMsg.bodyString()).contains("ok")
  }

  @Test
  fun `handler throw is HttpRequestFailure`() {
    val handler: HttpHandler = { throw RuntimeException("whisper boom") }
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v3/single-ok")
          .dynamicEnvironment("baseUrl", whisperBase)
          .httpClient(handler)
          .off()
      )
    val report = rundown.reportForStepName("o")!!
    assertThat(report.isSuccessful).isFalse()
    assertThat(report.exeTypeForFailure).isEqualTo(HTTP_REQUEST)
    assertThat(report.exeFailure).isInstanceOf(HttpRequestFailure::class.java)
    assertThat(report.exeFailure!!.failure.message).contains("whisper boom")
  }

  @Test
  fun `null handler response is NPE wrapped as HttpRequestFailure`() {
    @Suppress("UNCHECKED_CAST") val handler = { _: org.http4k.core.Request -> null } as HttpHandler
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v3/single-ok")
          .dynamicEnvironment("baseUrl", whisperBase)
          .httpClient(handler)
          .off()
      )
    val report = rundown.reportForStepName("o")!!
    assertThat(report.exeFailure).isInstanceOf(HttpRequestFailure::class.java)
    assertThat(report.exeFailure!!.failure).isInstanceOf(NullPointerException::class.java)
  }

  @Test
  fun `insecureHttp has no effect when httpClient is set`() {
    var calls = 0
    val handler: HttpHandler = { _ ->
      calls++
      Response(OK).body("{}")
    }
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v3/single-ok")
          .dynamicEnvironment("baseUrl", whisperBase)
          .insecureHttp(true)
          .httpClient(handler)
          .off()
      )
    assertThat(rundown.reportForStepName("o")!!.isSuccessful).isTrue()
    assertThat(calls).isEqualTo(1)
  }
}
