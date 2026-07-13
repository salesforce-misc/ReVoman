/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext
import com.salesforce.revoman.internal.postman.sandbox.PmSandbox
import com.salesforce.revoman.internal.postman.sandbox.PmScope
import com.salesforce.revoman.internal.postman.sandbox.ScriptTarget
import java.util.concurrent.TimeUnit
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
 * Measures the GraalJS sandbox hot path: repeated eval of a representative Postman test script.
 * Captures A2 (interpreter->JIT once via truffle-runtime + shared-Engine bootcode reuse) and A3
 * (JSON.parse closure reuse via pm.response.json()). One booted sandbox per trial; each @Benchmark
 * invocation is one eval — the steady-state per-script latency after warm-up.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
open class SandboxBenchmark {
  private lateinit var sandbox: PmSandbox

  private val script =
    """
    pm.test('status is ok', () => pm.expect(pm.response.code).to.eql(200));
    const body = pm.response.json();
    pm.environment.set('id', body.id);
    pm.test('has id', () => pm.expect(body.id).to.eql(42));
    """
      .trimIndent()

  private fun context() =
    PmExecutionContext(
      environment = PmScope("e", emptyMap()),
      response = mapOf("code" to 200, "status" to "OK", "body" to """{"id":42}"""),
    )

  @Setup(Level.Trial)
  fun setup() {
    sandbox = PmSandbox() // boots lazily on the first execute
  }

  @TearDown(Level.Trial) fun tearDown() = sandbox.close()

  @Benchmark
  fun evalPostmanTestScript(): Any? =
    sandbox.execute(script, ScriptTarget.TEST, context()).environment["id"]
}
