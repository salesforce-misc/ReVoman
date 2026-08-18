/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.salesforce.revoman.benchmark.scenario.RevUpV3Scenario
import com.salesforce.revoman.output.Rundown
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads

/** Thin adapter for fixed-profile warmed real-wire `revUp` operations. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Threads(1)
@OperationsPerInvocation(1)
open class RevUpV3WarmBenchmark {
  @JvmField @Param("v3-real-wire") var scenario: String = "v3-real-wire"

  private lateinit var workload: RevUpV3Scenario

  @Setup(Level.Trial)
  fun setup() {
    check(scenario == "v3-real-wire") { "Unsupported scenario: $scenario" }
    requirePerformanceLogging()
    workload = RevUpV3Scenario.start()
  }

  @Benchmark fun revUp(): Rundown = workload.execute()

  @TearDown(Level.Invocation) fun verifyInvocation() = workload.verifyInvocation()

  @TearDown(Level.Trial) fun close() = workload.close()
}
