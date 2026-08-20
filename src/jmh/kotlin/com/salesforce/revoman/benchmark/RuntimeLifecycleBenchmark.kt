/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.testing.http.MockHttpServer
import java.util.concurrent.TimeUnit
import org.apache.logging.log4j.Level as LogLevel
import org.apache.logging.log4j.core.config.Configurator
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup

/**
 * Measures a complete public-API kick lifecycle against a deterministic loopback server.
 *
 * Each invocation owns runtime/session construction and close. Script-free and scripted paths stay
 * separate so eager sandbox regressions cannot hide inside an aggregate score.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(1)
open class RuntimeLifecycleBenchmark {
  private lateinit var server: MockHttpServer
  private lateinit var scriptFreeKick: Kick
  private lateinit var scriptedKick: Kick

  @Setup(Level.Trial)
  fun setup() {
    Configurator.setRootLevel(LogLevel.OFF)
    server = MockHttpServer.start { Response(OK).body("""{"id":42}""") }
    scriptFreeKick = kick("pm-templates/v3/perf-lifecycle-script-free")
    scriptedKick = kick("pm-templates/v3/perf-lifecycle-scripted")
    verifiedScriptFree(ReVoman.revUp(scriptFreeKick))
    verifiedScripted(ReVoman.revUp(scriptedKick))
  }

  @Benchmark fun scriptFreeOneStep(): Rundown = verifiedScriptFree(ReVoman.revUp(scriptFreeKick))

  @Benchmark fun scriptedOneStep(): Rundown = verifiedScripted(ReVoman.revUp(scriptedKick))

  @TearDown(Level.Trial) fun tearDown() = server.close()

  private fun kick(templatePath: String): Kick =
    Kick.configure()
      .templatePath(templatePath)
      .dynamicEnvironment("baseUrl", server.baseUrl)
      .insecureHttp(true)
      .off()

  private fun verifiedScriptFree(rundown: Rundown): Rundown {
    check(rundown.stepReports.size == 1) { "script-free execution must contain exactly one step" }
    check(rundown.areAllStepsSuccessful) { "script-free execution must succeed" }
    return rundown
  }

  private fun verifiedScripted(rundown: Rundown): Rundown {
    check(rundown.stepReports.size == 1) { "scripted execution must contain exactly one step" }
    check(rundown.areAllStepsSuccessful) { "scripted execution must succeed" }
    check(rundown.mutableEnv["preRequestSeen"] == "yes") {
      "scripted execution must observe the pre-request mutation"
    }
    check(rundown.mutableEnv["responseId"] == "42") {
      "scripted execution must observe the test-script mutation"
    }
    return rundown
  }
}
