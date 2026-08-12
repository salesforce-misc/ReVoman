/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.HookConfig
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Proves every JS/hook boundary applies scope mutations before the next phase begins. */
class ScriptHookPhaseBarrierE2ETest {
  @Test
  fun `scripts and hooks observe each prior phase with isolated scopes and independent ledger controls`() {
    val hookOrder = mutableListOf<String>()
    val kick =
      Kick.configure()
        .templatePath("postman/phase-barrier/collection.postman_collection.json")
        .dynamicEnvironment("baseUrl", baseUrl)
        .dynamicEnvironment("phaseEnv", "initial")
        .dynamicEnvironment("phaseEnvGone", "initial")
        .dynamicEnvironment("phaseHookUnset", "initial")
        .dynamicEnvironment("phaseNumber", 1)
        .dynamicEnvironment("ledgerConsumed", "$baseUrl/phase-one?ledger=ledger-input")
        .hooks(
          HookConfig.pre(PreTxnStepPick.beforeStepName("phase-one")) { _, _, rundown ->
            hookOrder += "pre-hook"
            check(rundown.mutableEnv["phaseEnv"] == "pre-js")
            check(!rundown.mutableEnv.containsKey("phaseEnvGone"))
            check(rundown.mutableEnv.getInt("phaseNumber") == 2)
            check(rundown.collectionVariables["phaseCollection"] == "pre-js-collection")
            check(rundown.globals["phaseGlobal"] == "pre-js-global")
            check(!rundown.mutableEnv.containsKey("phaseCollection"))
            check(!rundown.mutableEnv.containsKey("phaseGlobal"))

            rundown.mutableEnv.set("phaseEnv", "pre-hook")
            rundown.mutableEnv.set("phaseEnvGone", "pre-hook-restored")
            rundown.mutableEnv.unset("phaseHookUnset")
            rundown.mutableEnv.set("phasePostHookUnset", "remove-in-post-hook")
            rundown.mutableEnv.set("ledgerProduced", "ledger-output")
            rundown.collectionVariables.set("phaseCollection", "pre-hook-collection")
            rundown.collectionVariables.set("phaseCollectionGone", "remove-in-post-js")
            rundown.globals.set("phaseGlobal", "pre-hook-global")
            rundown.globals.set("phaseGlobalGone", "remove-in-post-js")
          },
          HookConfig.post(PostTxnStepPick.afterStepName("phase-one")) { _, rundown ->
            hookOrder += "post-hook"
            check(rundown.mutableEnv["phaseEnv"] == "post-js")
            check(!rundown.mutableEnv.containsKey("phaseEnvGone"))
            check(rundown.mutableEnv.getInt("phaseNumber") == 2)
            check(rundown.collectionVariables["phaseCollection"] == "post-js-collection")
            check(!rundown.collectionVariables.containsKey("phaseCollectionGone"))
            check(rundown.globals["phaseGlobal"] == "post-js-global")
            check(!rundown.globals.containsKey("phaseGlobalGone"))
            check(!rundown.mutableEnv.containsKey("phaseCollection"))
            check(!rundown.mutableEnv.containsKey("phaseGlobal"))

            rundown.mutableEnv.set("phaseEnv", "post-hook")
            rundown.mutableEnv.unset("phasePostHookUnset")
            rundown.collectionVariables.set("phaseCollection", "post-hook-collection")
            rundown.collectionVariables.unset("phaseCollectionPostUnset")
            rundown.globals.set("phaseGlobal", "post-hook-global")
            rundown.globals.unset("phaseGlobalPostUnset")
          },
        )
        .insecureHttp(true)
        .off()

    val rundown = ReVoman.revUp(kick)

    assertThat(serverHits.get()).isEqualTo(2)
    assertThat(hookOrder).containsExactly("pre-hook", "post-hook").inOrder()
    assertThat(rundown.stepReports).hasSize(2)
    assertThat(rundown.areAllStepsSuccessful).isTrue()
    assertThat(rundown.stepReports.flatMap { it.pmTestAssertions }.map { it.name })
      .containsExactly(
        "pre-js sees initial host state",
        "pre-js scopes are isolated",
        "post-js sees pre-hook environment",
        "post-js sees pre-hook peer scopes",
        "post-js scopes stay isolated",
        "step-two sees post-hook environment",
        "step-two sees post-hook peer scopes",
        "step-two sees prior unsets",
        "step-two scopes stay isolated",
      )
      .inOrder()
    assertThat(rundown.stepReports.flatMap { it.pmTestAssertions }.all { it.passed }).isTrue()

    val phaseOne = rundown.stepReports.first()
    assertThat(phaseOne.envVars.produced)
      .containsExactly("phaseEnv", "phaseNumber", "ledgerProduced")
    assertThat(phaseOne.envVars.consumed).containsExactly("ledgerConsumed")
    assertThat(phaseOne.envVars.produced).doesNotContain("ledgerConsumed")
    assertThat(phaseOne.envVars.consumed).doesNotContain("ledgerProduced")
    assertThat(rundown.mutableEnv["phaseEnv"]).isEqualTo("post-hook")
    assertThat(rundown.mutableEnv["phaseNumber"]).isEqualTo(2)
    assertThat(rundown.collectionVariables["phaseCollection"]).isEqualTo("post-hook-collection")
    assertThat(rundown.globals["phaseGlobal"]).isEqualTo("post-hook-global")
  }

  companion object {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val serverHits = AtomicInteger()

    @BeforeAll
    @JvmStatic
    fun startServer() {
      serverHits.set(0)
      server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        serverHits.incrementAndGet()
        val body = "{\"ok\":true}".toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      server.start()
      baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterAll @JvmStatic fun stopServer() = server.stop(0)
  }
}
