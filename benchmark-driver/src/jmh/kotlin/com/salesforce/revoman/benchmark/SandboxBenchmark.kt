/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.salesforce.revoman.benchmark.driver.target.TargetOperation
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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
open class SandboxBenchmark {
    private val target = PreparedTargetState()
    private lateinit var operation: TargetOperation

    @Setup(Level.Trial)
    fun setup() {
        target.prepare(listOf(OPERATION_ID))
        operation = target.operation(OPERATION_ID)
    }

    @TearDown(Level.Trial)
    fun tearDown() = target.close()

    @Benchmark
    fun evalPostmanTestScript(): Long = operation.invoke()

    private companion object {
        const val OPERATION_ID: String = "sandbox.postman-test-script"
    }
}
