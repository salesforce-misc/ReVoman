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
import com.salesforce.revoman.testing.http.MockHttpServer
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
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
    private lateinit var fixture: MockHttpServer
    private lateinit var baseUrl: String

    @BeforeAll
    @JvmStatic
    fun startServer() {
      fixture = MockHttpServer.start { Response(OK).body("{}") }
      baseUrl = fixture.baseUrl
    }

    @AfterAll @JvmStatic fun stopServer() = fixture.close()
  }
}
