/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.target

import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import java.lang.ref.WeakReference

/** Stable identity for one target-version adapter surface. */
data class AdapterDescriptor(
    val id: String,
    val surfaceVersion: Int,
)

/** The only benchmark-driver interface allowed to bind target-version implementation details. */
interface TargetAdapter {
    val descriptor: AdapterDescriptor

    /** Resolves and caches every target binding needed by [request] outside timed execution. */
    fun prepare(runtime: TargetRuntime, request: WorkloadRequest): PreparedWorkload
}

/** Owns prepared target resources and exposes only target-independent scalar results. */
interface PreparedWorkload : AutoCloseable {
    /** Executes the prepared macro workload and returns a target-independent correctness digest. */
    fun execute(): ExecutionDigest

    /** Returns one cached scalar micro-operation by its versioned operation ID. */
    fun operation(id: String): TargetOperation
}

internal data class TrackedWeakReference(
    val type: String,
    val reference: WeakReference<*>,
)

internal interface LifecycleWeakReferenceProvider {
    fun drainLifecycleWeakReferences(): List<TrackedWeakReference>
}

internal const val EXECUTION_SESSION_WEAK_TYPE: String = "ExecutionSession"
internal const val KICK_EXECUTION_WEAK_TYPE: String = "KickExecution"
internal const val CS1_FAKE_EXECUTION_TOKEN_WEAK_TYPE: String = "Cs1FakeExecutionToken"

/** A cached target operation whose invocation cannot return a target-runtime object. */
fun interface TargetOperation {
    fun invoke(): Long
}

internal const val LIFECYCLE_WORKLOAD_ID: String = "lifecycle.no-script-one-step.v1"
internal const val COMPONENT_WORKLOAD_ID: String = "jmh.component-operations.v1"

internal fun executionDigest(executedSteps: Int, failureCount: Int): ExecutionDigest =
    ExecutionDigest(
        checksum = executedSteps * 31L + failureCount,
        executedSteps = executedSteps,
        failureCount = failureCount,
    )
