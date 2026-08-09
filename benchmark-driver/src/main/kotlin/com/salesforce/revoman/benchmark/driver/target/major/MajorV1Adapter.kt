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
import com.salesforce.revoman.benchmark.driver.target.TargetOperation
import com.salesforce.revoman.benchmark.driver.target.TargetRuntime
import com.salesforce.revoman.benchmark.driver.target.executionDigest
import java.lang.invoke.MethodHandle
import java.nio.file.Path

/** Adapter for the pinned major-v1 lifecycle contract; component operations are not yet supported. */
object MajorV1Adapter : TargetAdapter {
    override val descriptor: AdapterDescriptor = AdapterDescriptor(id = "major-v1", surfaceVersion = 1)

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
            val kick = target.type(MajorV1BindingContract.KICK_OWNER.className())
            val builder = target.type(MajorV1BindingContract.BUILDER_OWNER.className())
            val revoman = target.type(MajorV1BindingContract.REVOMAN_OWNER.className())
            val rundown = target.type(MajorV1BindingContract.RUNDOWN_OWNER.className())
            MajorPreparedWorkload(
                runtime = runtime,
                target = target,
                collectionPath = collectionPath,
                baseUrl = request.baseUrl,
                configure =
                    target.staticMethod(
                        kick,
                        MajorV1BindingContract.configure.name,
                        builder,
                    ),
                templatePath =
                    target.virtualMethod(
                        builder,
                        MajorV1BindingContract.templatePath.name,
                        builder,
                        String::class.java,
                    ),
                dynamicEnvironment =
                    target.virtualMethod(
                        builder,
                        MajorV1BindingContract.dynamicEnvironment.name,
                        builder,
                        String::class.java,
                        Any::class.java,
                    ),
                insecureHttp =
                    target.virtualMethod(
                        builder,
                        MajorV1BindingContract.insecureHttp.name,
                        builder,
                        Boolean::class.javaPrimitiveType!!,
                    ),
                off = target.virtualMethod(builder, MajorV1BindingContract.off.name, kick),
                revUp = target.staticMethod(revoman, MajorV1BindingContract.revUp.name, rundown, kick),
                executedStepCount =
                    target.virtualMethod(
                        rundown,
                        MajorV1BindingContract.executedStepCount.name,
                        Int::class.javaPrimitiveType!!,
                    ),
                unsuccessfulStepCount =
                    target.virtualMethod(
                        rundown,
                        MajorV1BindingContract.unsuccessfulStepCount.name,
                        Int::class.javaPrimitiveType!!,
                    ),
            )
        }
    }
}

private class MajorPreparedWorkload(
    private val runtime: TargetRuntime,
    private val target: ReflectiveTarget,
    private val collectionPath: String,
    private val baseUrl: String,
    private val configure: MethodHandle,
    private val templatePath: MethodHandle,
    private val dynamicEnvironment: MethodHandle,
    private val insecureHttp: MethodHandle,
    private val off: MethodHandle,
    private val revUp: MethodHandle,
    private val executedStepCount: MethodHandle,
    private val unsuccessfulStepCount: MethodHandle,
) : PreparedWorkload {
    private var closed: Boolean = false
    private val lifecycleOperation = TargetOperation { execute().checksum }

    override fun execute(): ExecutionDigest {
        check(!closed) { "major-v1 prepared workload is closed" }
        return runtime.withTargetContext {
            var builder: Any? = target.invoke(configure)
            target.invoke(templatePath, builder, collectionPath)
            target.invoke(dynamicEnvironment, builder, "baseUrl", baseUrl)
            target.invoke(insecureHttp, builder, true)
            var kick: Any? = target.invoke(off, builder)
            builder = null
            var rundown: Any? = target.invoke(revUp, kick)
            kick = null
            val executed = target.invoke(executedStepCount, rundown) as Int
            val unsuccessful = target.invoke(unsuccessfulStepCount, rundown) as Int
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

private fun String.className(): String = replace('/', '.')
