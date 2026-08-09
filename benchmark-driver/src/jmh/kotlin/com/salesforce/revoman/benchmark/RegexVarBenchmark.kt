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

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class RegexVarBenchmark {
    private val target = PreparedTargetState()
    private lateinit var mixedStrings: TargetOperation
    private lateinit var largeEnvironment: TargetOperation

    @Setup(Level.Trial)
    fun setup() {
        target.prepare(listOf(MIXED_STRINGS, LARGE_ENVIRONMENT))
        mixedStrings = target.operation(MIXED_STRINGS)
        largeEnvironment = target.operation(LARGE_ENVIRONMENT)
    }

    @TearDown(Level.Trial)
    fun tearDown() = target.close()

    @Benchmark
    fun replaceVariablesRecursivelyOverMixedStrings(): Long = mixedStrings.invoke()

    @Benchmark
    fun replaceVariablesInEnvOverLargeEnv(): Long = largeEnvironment.invoke()

    private companion object {
        const val MIXED_STRINGS: String = "regex.mixed-strings"
        const val LARGE_ENVIRONMENT: String = "regex.large-environment"
    }
}
