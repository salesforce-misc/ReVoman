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
import com.salesforce.revoman.benchmark.driver.target.LIFECYCLE_WORKLOAD_ID
import com.salesforce.revoman.benchmark.driver.target.PreparedWorkload
import com.salesforce.revoman.benchmark.driver.target.ReflectiveTarget
import com.salesforce.revoman.benchmark.driver.target.TargetAdapter
import com.salesforce.revoman.benchmark.driver.target.TargetCall0
import com.salesforce.revoman.benchmark.driver.target.TargetCall1
import com.salesforce.revoman.benchmark.driver.target.TargetCall2
import com.salesforce.revoman.benchmark.driver.target.TargetCall3
import com.salesforce.revoman.benchmark.driver.target.TargetOperation
import com.salesforce.revoman.benchmark.driver.target.TargetRuntime
import com.salesforce.revoman.benchmark.driver.target.executionDigest
import java.nio.file.Path

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
) : PreparedWorkload {
    private var closed: Boolean = false
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
            executionDigest(executed, unsuccessful)
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
    }
}
