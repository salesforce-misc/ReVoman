/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/** Trial-scoped target lifecycle prepared once outside every measured benchmark operation. */
@State(Scope.Thread)
open class WarmLifecycleAllocationState {
    private val prepared = PreparedTargetState()

    @Setup(Level.Trial)
    fun prepare() = prepared.prepareLifecycle()

    fun execute(): Long = prepared.executeLifecycle()

    @TearDown(Level.Trial)
    fun close() = prepared.close()
}

/** Measures exactly one complete prepared lifecycle execution per JMH operation. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class WarmLifecycleAllocationBenchmark {
    @Benchmark
    fun execute(state: WarmLifecycleAllocationState): Long = state.execute()
}
