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
import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.toJson
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Network-free E2E: a failing pm.test fails the step + Rundown, but the run continues by default.
 */
class PmTestFailureE2ETest {
  private val collection = "pm-templates/v3/pm-test-fail"

  @Test
  fun `failing pm test fails its step and Rundown, run continues to next step by default`() {
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath(collection)
          .dynamicEnvironment("baseUrl", baseUrl)
          .insecureHttp(true)
          .off()
      )
    // Both steps executed (run did NOT halt on the failing assertion).
    assertThat(rundown.stepReports).hasSize(2)
    val a = rundown.reportForStepName("a-fails")!!
    val b = rundown.reportForStepName("b-after")!!

    // The failing-assertion step is FAILED, tagged POST_RES_JS, with a pmTestFailure entry.
    assertThat(a.isSuccessful).isFalse()
    assertThat(a.exeTypeForFailure).isEqualTo(POST_RES_JS)
    assertThat(a.pmTestFailure).hasSize(1)

    // The following step ran and passed -> proves default continue.
    assertThat(b.isSuccessful).isTrue()

    // Rundown reflects the failure.
    assertThat(rundown.areAllStepsSuccessful).isFalse()

    // JSON output carries the failed assertion.
    val json = rundown.toJson()
    assertThat(json).contains("intentionally fails")
    assertThat(json).contains("post-res-js")
  }

  @Test
  fun `haltOnAnyFailure halts after the failing pm test step`() {
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath(collection)
          .dynamicEnvironment("baseUrl", baseUrl)
          .insecureHttp(true)
          .haltOnAnyFailure(true)
          .off()
      )
    // Halted after the first (failing) step -> the second step never executed.
    assertThat(rundown.stepReports).hasSize(1)
    assertThat(rundown.reportForStepName("a-fails")!!.isSuccessful).isFalse()
  }

  companion object {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @BeforeAll
    @JvmStatic
    fun startServer() {
      server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        val body = "{}".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      server.start()
      baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterAll @JvmStatic fun stopServer() = server.stop(0)
  }
}
