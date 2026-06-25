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
import com.salesforce.revoman.output.StopReason
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class ControlFlowE2ETest {

  private fun run(collection: String, factor: Int? = null) =
    ReVoman.revUp(
      Kick.configure()
        .templatePath(collection)
        .dynamicEnvironment("baseUrl", baseUrl)
        .insecureHttp(true)
        .let { if (factor != null) it.maxStepExecutionFactor(factor) else it }
        .off()
    )

  @Test
  fun `forward jump skips the intermediate step`() {
    val rundown = run("pm-templates/v3/cf-forward-jump")
    // a then c; b never executed.
    assertThat(rundown.stepReports.map { it.step.name }).containsExactly("a", "c").inOrder()
    assertThat(rundown.reportForStepName("b")).isNull()
    assertThat(rundown.stopReason).isEqualTo(StopReason.COMPLETED)
  }

  @Test
  fun `backward jump loops until the condition is met`() {
    val rundown = run("pm-templates/v3/cf-loop")
    // seed once + loop three times (n=1,2,3; jumps back while n<3).
    assertThat(rundown.reportsForStepName("loop")).hasSize(3)
    assertThat(rundown.reportsForStepName("loop").map { it.iteration })
      .containsExactly(0, 1, 2)
      .inOrder()
    assertThat(rundown.mutableEnv.getAsString("count")).isEqualTo("3")
    assertThat(rundown.stopReason).isEqualTo(StopReason.COMPLETED)
  }

  @Test
  fun `unbounded loop is bounded by the execution budget`() {
    // cf-loop with factor 1 => budget = 2 picked steps * 1 = 2 executions, loop wants more.
    val rundown = run("pm-templates/v3/cf-loop", factor = 1)
    assertThat(rundown.stopReason).isEqualTo(StopReason.LOOP_BUDGET_EXCEEDED)
  }

  @Test
  fun `setNextRequest null stops the run`() {
    val rundown = run("pm-templates/v3/cf-stop")
    assertThat(rundown.stopReason).isEqualTo(StopReason.STOPPED_BY_DIRECTIVE)
    assertThat(rundown.reportForStepName("b")).isNull()
  }

  @Test
  fun `skipRequest skips HTTP but the run continues`() {
    val before = hits.getOrDefault("/skipme", AtomicInteger(0)).get()
    val rundown = run("pm-templates/v3/cf-skip")
    val skipped = rundown.reportForStepName("skipme")!!
    assertThat(skipped.isRequestSkipped).isTrue()
    assertThat(skipped.isSuccessful).isTrue()
    // No HTTP reached the server for the skipped step.
    assertThat(hits.getOrDefault("/skipme", AtomicInteger(0)).get()).isEqualTo(before)
    // The following step ran.
    assertThat(rundown.reportForStepName("after")!!.isSuccessful).isTrue()
    assertThat(rundown.stopReason).isEqualTo(StopReason.COMPLETED)
  }

  @Test
  fun `unresolved jump target warns and continues linearly`() {
    val rundown = run("pm-templates/v3/cf-unresolved")
    // Both steps ran (linear continue), run completed.
    assertThat(rundown.reportForStepName("a")!!.isSuccessful).isTrue()
    assertThat(rundown.reportForStepName("b")!!.isSuccessful).isTrue()
    assertThat(rundown.stopReason).isEqualTo(StopReason.COMPLETED)
  }

  companion object {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val hits = ConcurrentHashMap<String, AtomicInteger>()

    @BeforeAll
    @JvmStatic
    fun startServer() {
      server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        hits.computeIfAbsent(exchange.requestURI.path) { AtomicInteger(0) }.incrementAndGet()
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
