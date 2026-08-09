/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.jmh

import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.profile.InternalProfiler
import org.openjdk.jmh.results.AggregationPolicy
import org.openjdk.jmh.results.IterationResult
import org.openjdk.jmh.results.Result
import org.openjdk.jmh.results.ScalarResult

/** Emits the real fork JVM PID once per iteration for strict observation provenance. */
class ForkPidProfiler : InternalProfiler {
    override fun getDescription(): String = "Records the current JMH fork JVM process ID"

    override fun beforeIteration(
        benchmarkParams: BenchmarkParams,
        iterationParams: IterationParams,
    ) = Unit

    override fun afterIteration(
        benchmarkParams: BenchmarkParams,
        iterationParams: IterationParams,
        result: IterationResult,
    ): Collection<Result<*>> =
        listOf(
            ScalarResult(
                FORK_PID_METRIC,
                ProcessHandle.current().pid().toDouble(),
                FORK_PID_UNIT,
                AggregationPolicy.AVG,
            )
        )
}

internal const val FORK_PID_METRIC: String = "revoman.fork.pid"
internal const val FORK_PID_UNIT: String = "pid"
