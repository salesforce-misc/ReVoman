/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.target.major

import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import com.salesforce.revoman.benchmark.driver.target.AdapterDescriptor
import com.salesforce.revoman.benchmark.driver.target.EXECUTION_SESSION_WEAK_TYPE
import com.salesforce.revoman.benchmark.driver.target.KICK_EXECUTION_WEAK_TYPE
import com.salesforce.revoman.benchmark.driver.target.LIFECYCLE_WORKLOAD_ID
import com.salesforce.revoman.benchmark.driver.target.LifecycleWeakReferenceProvider
import com.salesforce.revoman.benchmark.driver.target.PreparedWorkload
import com.salesforce.revoman.benchmark.driver.target.ReflectiveTarget
import com.salesforce.revoman.benchmark.driver.target.TargetAdapter
import com.salesforce.revoman.benchmark.driver.target.TargetCall0
import com.salesforce.revoman.benchmark.driver.target.TargetCall1
import com.salesforce.revoman.benchmark.driver.target.TargetCall2
import com.salesforce.revoman.benchmark.driver.target.TargetCall3
import com.salesforce.revoman.benchmark.driver.target.TargetOperation
import com.salesforce.revoman.benchmark.driver.target.TargetRuntime
import com.salesforce.revoman.benchmark.driver.target.TrackedWeakReference
import com.salesforce.revoman.benchmark.driver.target.executionDigest
import java.lang.ref.WeakReference
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Adapter for the pinned major-v1 lifecycle contract; component operations are not yet supported.
 */
object MajorV1Adapter : TargetAdapter {
    override val descriptor: AdapterDescriptor =
        AdapterDescriptor(id = "major-v1", surfaceVersion = 1)

    override fun prepare(runtime: TargetRuntime, request: WorkloadRequest): PreparedWorkload {
        require(request.id == LIFECYCLE_WORKLOAD_ID) {
            "major-v1 supports only $LIFECYCLE_WORKLOAD_ID, not ${request.id}"
        }
        require(request.contractVersion == 1) {
            "major-v1 requires lifecycle contract version 1, not ${request.contractVersion}"
        }
        val collectionPath =
            Path.of(request.fixtureRoot).resolve("collection.postman_collection.json").toString()
        return runtime.withTargetContext {
            val target = ReflectiveTarget(runtime)
            MajorPreparedWorkload(
                runtime = runtime,
                collectionPath = collectionPath,
                baseUrl = request.baseUrl,
                configure = MajorV1BindingContract.configure.bind(target).call0(),
                templatePath = MajorV1BindingContract.templatePath.bind(target).call2(),
                dynamicEnvironment = MajorV1BindingContract.dynamicEnvironment.bind(target).call3(),
                insecureHttp = MajorV1BindingContract.insecureHttp.bind(target).call2(),
                off = MajorV1BindingContract.off.bind(target).call1(),
                revUp = MajorV1BindingContract.revUp.bind(target).call1(),
                executedStepCount = MajorV1BindingContract.executedStepCount.bind(target).call1(),
                unsuccessfulStepCount =
                    MajorV1BindingContract.unsuccessfulStepCount.bind(target).call1(),
            )
        }
    }
}

private class MajorPreparedWorkload(
    private val runtime: TargetRuntime,
    private val collectionPath: String,
    private val baseUrl: String,
    private val configure: TargetCall0,
    private val templatePath: TargetCall2,
    private val dynamicEnvironment: TargetCall3,
    private val insecureHttp: TargetCall2,
    private val off: TargetCall1,
    private val revUp: TargetCall1,
    private val executedStepCount: TargetCall1,
    private val unsuccessfulStepCount: TargetCall1,
) : PreparedWorkload, LifecycleWeakReferenceProvider {
    private var closed: Boolean = false
    private var executionCount: Int = 0
    private var drainLifecycleDiagnostics: TargetCall0? = null
    private val lifecycleOperation = TargetOperation { execute().checksum }

    override fun execute(): ExecutionDigest {
        check(!closed) { "major-v1 prepared workload is closed" }
        return runtime.withTargetContext {
            var builder: Any? = configure.invoke()
            templatePath.invoke(builder, collectionPath)
            dynamicEnvironment.invoke(builder, "baseUrl", baseUrl)
            insecureHttp.invoke(builder, true)
            var kick: Any? = off.invoke(builder)
            builder = null
            var rundown: Any? = revUp.invoke(kick)
            kick = null
            val executed = executedStepCount.invoke(rundown) as Int
            val unsuccessful = unsuccessfulStepCount.invoke(rundown) as Int
            rundown = null
            executionCount = Math.addExact(executionCount, 1)
            executionDigest(executed, unsuccessful)
        }
    }

    override fun drainLifecycleWeakReferences(): List<TrackedWeakReference> =
        drainLifecycleWeakReferences(observer = null)

    fun drainLifecycleWeakReferences(
        observer: LifecycleNormalizationObserver?
    ): List<TrackedWeakReference> {
        check(!closed) { "major-v1 prepared workload is closed" }
        check(executionCount > 0) { "major-v1 lifecycle evidence requires retained executions" }
        return runtime.withTargetContext {
            val handle =
                drainLifecycleDiagnostics
                    ?: MajorV1BindingContract.drainLifecycleDiagnostics
                        .bind(ReflectiveTarget(runtime))
                        .call0()
                        .also { drainLifecycleDiagnostics = it }
            var raw: Any? = handle.invoke()
            check(raw?.javaClass === Array<Any>::class.java) {
                "major-v1 lifecycle drain must return exact Object[]"
            }
            @Suppress("UNCHECKED_CAST")
            val array = raw as Array<Any>
            val normalized = normalizeLifecycleRecords(array, handle, observer)
            raw = null
            normalized
        }
    }

    override fun operation(id: String): TargetOperation {
        check(!closed) { "major-v1 prepared workload is closed" }
        return when (id) {
            LIFECYCLE_WORKLOAD_ID -> lifecycleOperation
            else ->
                throw UnsupportedOperationException(
                    "major-v1 does not support target operation: $id"
                )
        }
    }

    override fun close() {
        closed = true
        drainLifecycleDiagnostics = null
    }
}

internal fun drainMajorLifecycleWeakReferencesForTest(
    prepared: PreparedWorkload,
    observer: LifecycleNormalizationObserver,
): List<TrackedWeakReference> {
    check(prepared is MajorPreparedWorkload) {
        "Lifecycle normalization observer requires a major-v1 prepared workload"
    }
    return prepared.drainLifecycleWeakReferences(observer)
}

internal fun interface LifecycleNormalizationObserver {
    fun observe(
        rawArray: Array<Any>,
        drainHandle: TargetCall0,
        firstReferent: Any?,
        secondReferent: Any?,
    )
}

private fun normalizeLifecycleRecords(
    raw: Array<Any>,
    drainHandle: TargetCall0,
    observer: LifecycleNormalizationObserver?,
): List<TrackedWeakReference> {
    val normalized = normalizeLifecycleRecords(raw, initialCount = 0, maximumCount = Int.MAX_VALUE.toLong())
    observer?.observe(
        raw,
        drainHandle,
        normalized.getOrNull(0)?.reference?.get(),
        normalized.getOrNull(1)?.reference?.get(),
    )
    return normalized
}

internal fun normalizeMajorLifecycleRecordsForTest(
    raw: Array<Any>,
    initialCount: Long,
    maximumCount: Long = Int.MAX_VALUE.toLong(),
): List<TrackedWeakReference> =
    normalizeLifecycleRecords(raw, initialCount, maximumCount)

private fun normalizeLifecycleRecords(
    raw: Array<Any>,
    initialCount: Long,
    maximumCount: Long,
): List<TrackedWeakReference> {
    check(raw.isNotEmpty() && raw.size % 2 == 0) {
        "major-v1 lifecycle drain requires nonempty type/reference pairs"
    }
    check(initialCount >= 0 && maximumCount >= 0) {
        "major-v1 lifecycle record count bounds must not be negative"
    }
    val identities =
        Collections.newSetFromMap(IdentityHashMap<WeakReference<*>, Boolean>())
    val normalized = ArrayList<TrackedWeakReference>(raw.size / 2)
    var recordCount = initialCount
    raw.indices.step(2).forEach { index ->
        val nextCount = Math.addExact(recordCount, 1)
        check(nextCount <= maximumCount) {
            "major-v1 lifecycle record count exceeds the supported maximum"
        }
        val type = raw[index]
        check(type is String && type in EXPECTED_LIFECYCLE_TYPES) {
            "major-v1 lifecycle type is unsupported"
        }
        val reference = raw[index + 1]
        check(reference is WeakReference<*> && reference.javaClass === WeakReference::class.java) {
            "major-v1 lifecycle reference must be an exact WeakReference"
        }
        check(identities.add(reference)) {
            "major-v1 lifecycle reference identity is repeated"
        }
        val normalizedType =
            when (type) {
                EXECUTION_SESSION_WEAK_TYPE -> EXECUTION_SESSION_WEAK_TYPE
                KICK_EXECUTION_WEAK_TYPE -> KICK_EXECUTION_WEAK_TYPE
                else -> error("validated lifecycle type is unreachable")
            }
        normalized += TrackedWeakReference(normalizedType, reference)
        recordCount = nextCount
    }
    return normalized.toList()
}

private val EXPECTED_LIFECYCLE_TYPES =
    setOf(EXECUTION_SESSION_WEAK_TYPE, KICK_EXECUTION_WEAK_TYPE)
