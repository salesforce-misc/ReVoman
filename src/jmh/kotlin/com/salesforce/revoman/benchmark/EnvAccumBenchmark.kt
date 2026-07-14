/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole

/**
 * M steps, each: set a fresh key + capture a per-step env snapshot (mirrors ReVoman.runStep's
 * pmEnvSnapshot). Measures env-accumulation cost — O(M*E) with a copy-on-snapshot backing, O(M)
 * once E2's persistent map makes the snapshot an O(1) structural share.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class EnvAccumBenchmark {
  @Param("50", "200", "800") open var steps: Int = 0

  private lateinit var stepList: List<Step>

  @Setup
  fun setUp() {
    stepList = (0 until steps).map { Step(index = "$it", rawPMStep = Item(name = "s$it")) }
  }

  @Benchmark
  open fun accumulateAndSnapshot(bh: Blackhole) {
    val env = PostmanEnvironment<Any?>()
    stepList.forEach { step ->
      env.currentStep = step
      env.set("key_${step.name}", step.name)
      // Mirror ReVoman.runStep's per-step snapshot capture:
      bh.consume(env.copy(mutableEnv = env.mutableEnv.toMutableMap()))
    }
    bh.consume(env)
  }
}
