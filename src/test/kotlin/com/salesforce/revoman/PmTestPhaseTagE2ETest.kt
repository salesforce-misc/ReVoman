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
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Proves PmJsEval stamps each pm.test assertion with the phase that produced it. Network-free. */
class PmTestPhaseTagE2ETest {
  @Test
  fun `assertions are tagged with their script phase`() {
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v3/pm-test-phases")
          .dynamicEnvironment("baseUrl", baseUrl)
          .insecureHttp(true)
          .off()
      )
    val report = rundown.stepReports.single()
    val byName = report.pmTestAssertions.associateBy { it.name }
    assertThat(byName["pre-req assertion runs"]!!.exeType).isEqualTo(PRE_REQ_JS)
    assertThat(byName["post-res assertion runs"]!!.exeType).isEqualTo(POST_RES_JS)
    // All passed -> step is successful.
    assertThat(report.isSuccessful).isTrue()
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
