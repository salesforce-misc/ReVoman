/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.target.baseline

import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.ExecutionDigest
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import com.salesforce.revoman.benchmark.driver.target.AdapterDescriptor
import com.salesforce.revoman.benchmark.driver.target.COMPONENT_WORKLOAD_ID
import com.salesforce.revoman.benchmark.driver.target.LIFECYCLE_WORKLOAD_ID
import com.salesforce.revoman.benchmark.driver.target.PreparedWorkload
import com.salesforce.revoman.benchmark.driver.target.ReflectiveTarget
import com.salesforce.revoman.benchmark.driver.target.TargetAdapter
import com.salesforce.revoman.benchmark.driver.target.TargetCall0
import com.salesforce.revoman.benchmark.driver.target.TargetCall1
import com.salesforce.revoman.benchmark.driver.target.TargetCall2
import com.salesforce.revoman.benchmark.driver.target.TargetCall3
import com.salesforce.revoman.benchmark.driver.target.TargetCall5
import com.salesforce.revoman.benchmark.driver.target.TargetCall6
import com.salesforce.revoman.benchmark.driver.target.TargetOperation
import com.salesforce.revoman.benchmark.driver.target.TargetRuntime
import com.salesforce.revoman.benchmark.driver.target.executionDigest
import com.squareup.moshi.JsonClass
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Path

/** Cached reflective adapter for the exact baseline commit 83f3cd70 target surface. */
object Baseline083f3cd70Adapter : TargetAdapter {
    override val descriptor: AdapterDescriptor =
        AdapterDescriptor(id = "baseline-83f3cd70", surfaceVersion = 1)

    override fun prepare(runtime: TargetRuntime, request: WorkloadRequest): PreparedWorkload =
        when (request.id) {
            LIFECYCLE_WORKLOAD_ID -> prepareLifecycle(runtime, request)
            COMPONENT_WORKLOAD_ID -> prepareComponentOperations(runtime, request)
            else -> error("baseline-83f3cd70 does not support workload: ${request.id}")
        }

    private fun prepareLifecycle(
        runtime: TargetRuntime,
        request: WorkloadRequest,
    ): PreparedWorkload {
        require(request.contractVersion == 1) {
            "baseline-83f3cd70 requires lifecycle contract version 1, not ${request.contractVersion}"
        }
        val collectionPath =
            Path.of(request.fixtureRoot).resolve("collection.postman_collection.json").toString()
        return runtime.withTargetContext {
            val target = ReflectiveTarget(runtime)
            val kick = target.type(KICK)
            val builder = target.type(KICK_BUILDER)
            val revoman = target.type(REVOMAN)
            val rundown = target.type(RUNDOWN)
            val stepReport = target.type(STEP_REPORT)
            val runLogSink = target.type(RUN_LOG_SINK)
            val noOpSink = target.type(NO_OP_RUN_LOG_SINK)
            BaselineLifecyclePreparedWorkload(
                runtime = runtime,
                collectionPath = collectionPath,
                baseUrl = request.baseUrl,
                configure = target.staticMethod(kick, "configure", builder).call0(),
                templatePath =
                    target
                        .virtualMethod(builder, "templatePath", builder, String::class.java)
                        .call2(),
                dynamicEnvironment =
                    target
                        .virtualMethod(
                            builder,
                            "dynamicEnvironment",
                            builder,
                            String::class.java,
                            Any::class.java,
                        )
                        .call3(),
                insecureHttp =
                    target
                        .virtualMethod(
                            builder,
                            "insecureHttp",
                            builder,
                            Boolean::class.javaPrimitiveType!!,
                        )
                        .call2(),
                hooks =
                    target.virtualMethod(builder, "hooks", builder, Iterable::class.java).call2(),
                pollingConfig =
                    target
                        .virtualMethod(builder, "pollingConfig", builder, Iterable::class.java)
                        .call2(),
                runLogSink =
                    target.virtualMethod(builder, "runLogSink", builder, runLogSink).call2(),
                noOpSink = target.fieldGetter(noOpSink, "INSTANCE", noOpSink).call0().invoke(),
                off = target.virtualMethod(builder, "off", kick).call1(),
                revUp = target.staticMethod(revoman, "revUp", rundown, kick).call1(),
                stepReports = target.fieldGetter(rundown, "stepReports", List::class.java).call1(),
                successful =
                    target
                        .fieldGetter(
                            stepReport,
                            "isSuccessful",
                            Boolean::class.javaPrimitiveType!!,
                        )
                        .call1(),
            )
        }
    }

    private fun prepareComponentOperations(
        runtime: TargetRuntime,
        request: WorkloadRequest,
    ): PreparedWorkload = BaselineComponentPreparedWorkload.prepare(runtime, request)

    private const val KICK = "com.salesforce.revoman.input.config.Kick"
    private const val KICK_BUILDER = "com.salesforce.revoman.input.config.Kick\$Builder"
    private const val REVOMAN = "com.salesforce.revoman.ReVoman"
    private const val RUNDOWN = "com.salesforce.revoman.output.Rundown"
    private const val STEP_REPORT = "com.salesforce.revoman.output.report.StepReport"
    private const val RUN_LOG_SINK = "com.salesforce.revoman.output.log.RunLogSink"
    private const val NO_OP_RUN_LOG_SINK = "com.salesforce.revoman.output.log.RunLogSink\$NoOp"
}

private class BaselineLifecyclePreparedWorkload(
    private val runtime: TargetRuntime,
    private val collectionPath: String,
    private val baseUrl: String,
    private val configure: TargetCall0,
    private val templatePath: TargetCall2,
    private val dynamicEnvironment: TargetCall3,
    private val insecureHttp: TargetCall2,
    private val hooks: TargetCall2,
    private val pollingConfig: TargetCall2,
    private val runLogSink: TargetCall2,
    private var noOpSink: Any?,
    private val off: TargetCall1,
    private val revUp: TargetCall1,
    private val stepReports: TargetCall1,
    private val successful: TargetCall1,
) : PreparedWorkload {
    private var closed: Boolean = false
    private val lifecycleOperation = TargetOperation { execute().checksum }

    override fun execute(): ExecutionDigest {
        check(!closed) { "baseline-83f3cd70 prepared workload is closed" }
        return runtime.withTargetContext {
            var builder: Any? = configure.invoke()
            templatePath.invoke(builder, collectionPath)
            dynamicEnvironment.invoke(builder, "baseUrl", baseUrl)
            insecureHttp.invoke(builder, true)
            hooks.invoke(builder, emptyList<Any>())
            pollingConfig.invoke(builder, emptyList<Any>())
            runLogSink.invoke(builder, noOpSink)
            var kick: Any? = off.invoke(builder)
            builder = null
            var rundown: Any? = revUp.invoke(kick)
            kick = null
            var reports: List<*>? = stepReports.invoke(rundown) as List<*>
            rundown = null
            val executed = checkNotNull(reports).size
            val failures =
                checkNotNull(reports).count { report ->
                    !(successful.invoke(report) as Boolean)
                }
            reports = null
            executionDigest(executed, failures)
        }
    }

    override fun operation(id: String): TargetOperation {
        check(!closed) { "baseline-83f3cd70 prepared workload is closed" }
        return when (id) {
            LIFECYCLE_WORKLOAD_ID -> lifecycleOperation
            else ->
                throw UnsupportedOperationException(
                    "baseline-83f3cd70 lifecycle workload does not support target operation: $id"
                )
        }
    }

    override fun close() {
        noOpSink = null
        closed = true
    }
}

private class BaselineComponentPreparedWorkload
private constructor(
    private val runtime: TargetRuntime,
    private val regexReplace: TargetCall3,
    private val regexReplaceEnvironment: TargetCall2,
    private var regexReplacer: Any?,
    private var postmanSdk: Any?,
    private val mixedStrings: List<String>,
    private val fromJson: TargetCall5,
    private val toJson: TargetCall6,
    private val compositeType: Class<*>,
    private val typedAdapters: Map<Type, Any?>,
    private val compositeJson: String,
    private var composite: Any?,
    private val scopeConstructor: TargetCall3,
    private val contextConstructor: TargetCall5,
    private val sandboxExecute: TargetCall5,
    private val resultEnvironment: TargetCall1,
    private var sandbox: Any?,
    private var scriptTarget: Any?,
    private val script: String,
    private val resourceCloser: BaselineResourceCloser,
    private val persistentMapConstructor: TargetCall0,
    private val environmentConstructor: TargetCall1,
    private val setCurrentStep: TargetCall2,
    private val environmentSet: TargetCall3,
    private val environmentSnapshot: TargetCall1,
    private val stepName: TargetCall1,
    private var steps: List<Any?>,
    private val engineCreate: TargetCall0,
    private val engineClose: TargetCall1,
) : PreparedWorkload {
    private var closed: Boolean = false
    private val sandboxTimeoutMillis: Any = 5_000L
    private val operations: Map<String, TargetOperation> =
        mapOf(
            "smoke.sum-range" to scalarOperation(::sumRange),
            "regex.mixed-strings" to scalarOperation(::replaceMixedStrings),
            "regex.large-environment" to scalarOperation(::replaceLargeEnvironment),
            "marshalling.composite-from-json" to scalarOperation(::compositeFromJson),
            "marshalling.composite-to-json" to scalarOperation(::compositeToJson),
            "sandbox.postman-test-script" to scalarOperation(::executeSandboxScript),
            "environment.accumulate-and-snapshot" to scalarOperation(::accumulateEnvironment),
            "graal.open-engine" to scalarOperation(::openGraalEngine),
        )

    override fun execute(): ExecutionDigest =
        throw UnsupportedOperationException(
            "jmh.component-operations.v1 exposes scalar operations, not a macro execution"
        )

    override fun operation(id: String): TargetOperation {
        check(!closed) { "baseline-83f3cd70 prepared workload is closed" }
        return operations[id]
            ?: throw UnsupportedOperationException("Unknown baseline target operation: $id")
    }

    override fun close() {
        if (closed) return
        closed = true
        val ownedSandbox = sandbox
        val ownedPostmanSdk = postmanSdk
        sandbox = null
        scriptTarget = null
        regexReplacer = null
        postmanSdk = null
        composite = null
        steps = emptyList()
        runtime.withTargetContext { resourceCloser.close(ownedSandbox, ownedPostmanSdk) }
    }

    private fun scalarOperation(block: () -> Long): TargetOperation = TargetOperation {
        check(!closed) { "baseline-83f3cd70 prepared workload is closed" }
        runtime.withTargetContext(block)
    }

    private fun sumRange(): Long = (1L..100L).sum()

    private fun replaceMixedStrings(): Long = mixedStrings.sumOf { value ->
        (regexReplace.invoke(requireNotNull(regexReplacer), value, postmanSdk) as String)
            .length
            .toLong()
    }

    private fun replaceLargeEnvironment(): Long {
        var result: Map<*, *>? =
            regexReplaceEnvironment.invoke(requireNotNull(regexReplacer), postmanSdk) as Map<*, *>
        val size = result!!.size.toLong()
        result = null
        return size
    }

    private fun compositeFromJson(): Long {
        var result: Any? =
            fromJson.invoke(
                compositeType,
                compositeJson,
                emptyList<Any>(),
                typedAdapters,
                emptySet<Class<*>>(),
            )
        checkNotNull(result) { "Baseline composite JSON unexpectedly parsed as null" }
        result = null
        return compositeJson.length.toLong()
    }

    private fun compositeToJson(): Long {
        var result: String? =
            toJson.invoke(
                compositeType,
                composite,
                emptyList<Any>(),
                typedAdapters,
                emptySet<Class<*>>(),
                "  ",
            ) as String
        val length = result!!.length.toLong()
        result = null
        return length
    }

    private fun executeSandboxScript(): Long {
        var environment: Any? = scopeConstructor.invoke("e", emptyMap<String, Any>(), null)
        var globals: Any? = scopeConstructor.invoke("globals", emptyMap<String, Any>(), null)
        var collectionVariables: Any? =
            scopeConstructor.invoke(
                "collectionVariables",
                emptyMap<String, Any>(),
                null,
            )
        var context: Any? =
            contextConstructor.invoke(
                environment,
                globals,
                collectionVariables,
                null,
                mapOf("code" to 200, "status" to "OK", "body" to "{\"id\":42}"),
            )
        environment = null
        globals = null
        collectionVariables = null
        var result: Any? =
            sandboxExecute.invoke(sandbox, script, scriptTarget, context, sandboxTimeoutMillis)
        context = null
        var values: Map<*, *>? = resultEnvironment.invoke(result) as Map<*, *>
        result = null
        val id = (values!!["id"] as Number).toLong()
        values = null
        return id
    }

    private fun accumulateEnvironment(): Long {
        var backing: Any? = persistentMapConstructor.invoke()
        var environment: Any? = environmentConstructor.invoke(backing)
        backing = null
        var checksum = 0L
        steps.forEach { step ->
            setCurrentStep.invoke(environment, step)
            val name = stepName.invoke(step) as String
            environmentSet.invoke(environment, "key_$name", name)
            var snapshot: Any? = environmentSnapshot.invoke(environment)
            checksum += (snapshot as Map<*, *>).size
            snapshot = null
        }
        environment = null
        return checksum
    }

    private fun openGraalEngine(): Long {
        var engine: Any? = engineCreate.invoke()
        return try {
            1
        } finally {
            try {
                engineClose.invoke(engine)
            } finally {
                engine = null
            }
        }
    }

    companion object {
        fun prepare(runtime: TargetRuntime, request: WorkloadRequest): PreparedWorkload {
            require(request.contractVersion == 1) {
                "baseline-83f3cd70 requires component contract version 1, not ${request.contractVersion}"
            }
            val fixtureRoot = Path.of(request.fixtureRoot)
            val compositeJson = Files.readString(fixtureRoot.resolve("composite-response.json"))
            val script = Files.readString(fixtureRoot.resolve("postman-test-script.js"))
            val regexInputs =
                BenchmarkJson.read<BaselineRegexInputs>(fixtureRoot.resolve("regex-inputs.json"))
            val stepCount = request.parameters["steps"]?.toInt() ?: 50
            require(stepCount > 0) { "steps must be positive" }
            return runtime.withTargetContext {
                prepareInTargetContext(
                    runtime = runtime,
                    compositeJson = compositeJson,
                    script = script,
                    regexInputs = regexInputs,
                    stepCount = stepCount,
                )
            }
        }

        private fun prepareInTargetContext(
            runtime: TargetRuntime,
            compositeJson: String,
            script: String,
            regexInputs: BaselineRegexInputs,
            stepCount: Int,
        ): PreparedWorkload {
            val target = ReflectiveTarget(runtime)
            val moshi = target.type("com.salesforce.revoman.internal.json.MoshiReVoman")
            val moshiCompanion =
                target.type("com.salesforce.revoman.internal.json.MoshiReVoman\$Companion")
            val regex = target.type("com.salesforce.revoman.internal.postman.RegexReplacer")
            val sdk = target.type("com.salesforce.revoman.internal.postman.PostmanSDK")
            val environment =
                target.type("com.salesforce.revoman.output.postman.PostmanEnvironment")
            val sandbox = target.type("com.salesforce.revoman.internal.postman.sandbox.PmSandbox")
            val evaluator =
                target.type("com.salesforce.revoman.internal.postman.PostmanSDK\$JSEvaluator")
            val resourceCloser =
                BaselineResourceCloser(
                    sandboxClose = target.virtualMethod(sandbox, "close", Void.TYPE).call1(),
                    sdkJsEvaluator = target.fieldGetter(sdk, "jsEvaluator").call1(),
                    evaluatorContext = target.fieldGetter(evaluator, "jsContext").call1(),
                )
            var postmanSdk: Any? = null
            var sandboxInstance: Any? = null
            try {
                val companion =
                    target.fieldGetter(moshi, "Companion", moshiCompanion).call0().invoke()
                val initMoshi =
                    target
                        .virtualMethod(
                            moshiCompanion,
                            "initMoshi\$com_salesforce_revoman_revoman",
                            moshi,
                        )
                        .call1()
                val regexConstructor = target.constructor(regex).call0()
                val sdkConstructor =
                    target
                        .constructor(sdk, moshi, String::class.java, regex, Map::class.java)
                        .call4()
                val regexReplacer = regexConstructor.invoke()
                postmanSdk =
                    sdkConstructor.invoke(
                        initMoshi.invoke(companion),
                        null,
                        regexReplacer,
                        mutableMapOf<String, Any?>(),
                    )
                val sdkEnvironment = target.fieldGetter(sdk, "environment", environment).call1()
                val environmentSet =
                    target
                        .virtualMethod(
                            environment,
                            "set",
                            Void.TYPE,
                            String::class.java,
                            Any::class.java,
                        )
                        .call3()
                regexInputs.environment.forEach { (key, value) ->
                    environmentSet.invoke(
                        sdkEnvironment.invoke(postmanSdk),
                        key,
                        value,
                    )
                }

                val jsonUtils = target.type("com.salesforce.revoman.input.json.JsonPojoUtils")
                val compositeType =
                    target.type(
                        "com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse"
                    )
                val responseType =
                    target.type(
                        "com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse\$Response"
                    )
                val adapterFactory = target.type("com.squareup.moshi.JsonAdapter\$Factory")
                val either = target.type("io.vavr.control.Either")
                val adapter =
                    target.fieldGetter(compositeType, "ADAPTER", adapterFactory).call0().invoke()
                val eitherRight =
                    target.staticMethod(either, "right", either, Any::class.java).call1()
                val typedAdapters: Map<Type, Any?> =
                    mapOf(responseType to eitherRight.invoke(adapter))
                val fromJson =
                    target
                        .staticMethod(
                            jsonUtils,
                            "jsonToPojo",
                            Any::class.java,
                            Type::class.java,
                            String::class.java,
                            List::class.java,
                            Map::class.java,
                            Set::class.java,
                        )
                        .call5()
                val toJson =
                    target
                        .staticMethod(
                            jsonUtils,
                            "pojoToJson",
                            String::class.java,
                            Type::class.java,
                            Any::class.java,
                            List::class.java,
                            Map::class.java,
                            Set::class.java,
                            String::class.java,
                        )
                        .call6()
                val composite =
                    fromJson.invoke(
                        compositeType,
                        compositeJson,
                        emptyList<Any>(),
                        typedAdapters,
                        emptySet<Class<*>>(),
                    )

                val scope = target.type("com.salesforce.revoman.internal.postman.sandbox.PmScope")
                val context =
                    target.type(
                        "com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext"
                    )
                val scriptTarget =
                    target.type("com.salesforce.revoman.internal.postman.sandbox.ScriptTarget")
                val sandboxResult =
                    target.type("com.salesforce.revoman.internal.postman.sandbox.PmExecutionResult")
                val testTarget =
                    target.fieldGetter(scriptTarget, "TEST", scriptTarget).call0().invoke()
                sandboxInstance = target.constructor(sandbox).call0().invoke()

                val item = target.type("com.salesforce.revoman.internal.postman.template.Item")
                val request =
                    target.type("com.salesforce.revoman.internal.postman.template.Request")
                val folder = target.type("com.salesforce.revoman.output.report.Folder")
                val step = target.type("com.salesforce.revoman.output.report.Step")
                val itemConstructor =
                    target
                        .constructor(
                            item,
                            String::class.java,
                            List::class.java,
                            request,
                            List::class.java,
                            String::class.java,
                        )
                        .call5()
                val requestConstructor = target.constructor(request).call0()
                val stepConstructor =
                    target
                        .constructor(
                            step,
                            String::class.java,
                            item,
                            folder,
                            String::class.java,
                        )
                        .call4()
                val steps =
                    (0 until stepCount).map { index ->
                        val targetItem =
                            itemConstructor.invoke(
                                "s$index",
                                null,
                                requestConstructor.invoke(),
                                null,
                                "",
                            )
                        stepConstructor.invoke(index.toString(), targetItem, null, "")
                    }

                val persistentMap =
                    target.type("com.salesforce.revoman.output.postman.PersistentBackedMutableMap")
                val engine = target.type("org.graalvm.polyglot.Engine")
                return BaselineComponentPreparedWorkload(
                    runtime = runtime,
                    regexReplace =
                        target
                            .virtualMethod(
                                regex,
                                "replaceVariablesRecursively\$com_salesforce_revoman_revoman",
                                String::class.java,
                                String::class.java,
                                sdk,
                            )
                            .call3(),
                    regexReplaceEnvironment =
                        target
                            .virtualMethod(
                                regex,
                                "replaceVariablesInEnv\$com_salesforce_revoman_revoman",
                                Map::class.java,
                                sdk,
                            )
                            .call2(),
                    regexReplacer = regexReplacer,
                    postmanSdk = postmanSdk,
                    mixedStrings = regexInputs.mixedStrings,
                    fromJson = fromJson,
                    toJson = toJson,
                    compositeType = compositeType,
                    typedAdapters = typedAdapters,
                    compositeJson = compositeJson,
                    composite = composite,
                    scopeConstructor =
                        target
                            .constructor(
                                scope,
                                String::class.java,
                                Map::class.java,
                                String::class.java,
                            )
                            .call3(),
                    contextConstructor =
                        target
                            .constructor(
                                context,
                                scope,
                                scope,
                                scope,
                                Map::class.java,
                                Map::class.java,
                            )
                            .call5(),
                    sandboxExecute =
                        target
                            .virtualMethod(
                                sandbox,
                                "execute",
                                sandboxResult,
                                String::class.java,
                                scriptTarget,
                                context,
                                Long::class.javaPrimitiveType!!,
                            )
                            .call5(),
                    resultEnvironment =
                        target
                            .virtualMethod(sandboxResult, "getEnvironment", Map::class.java)
                            .call1(),
                    sandbox = sandboxInstance,
                    scriptTarget = testTarget,
                    script = script,
                    resourceCloser = resourceCloser,
                    persistentMapConstructor = target.constructor(persistentMap).call0(),
                    environmentConstructor =
                        target.constructor(environment, Map::class.java).call1(),
                    setCurrentStep =
                        target
                            .virtualMethod(
                                environment,
                                "setCurrentStep\$com_salesforce_revoman_revoman",
                                Void.TYPE,
                                step,
                            )
                            .call2(),
                    environmentSet = environmentSet,
                    environmentSnapshot =
                        target.virtualMethod(environment, "o1Snapshot", environment).call1(),
                    stepName = target.fieldGetter(step, "name", String::class.java).call1(),
                    steps = steps,
                    engineCreate = target.staticMethod(engine, "create", engine).call0(),
                    engineClose = target.virtualMethod(engine, "close", Void.TYPE).call1(),
                )
            } catch (failure: Throwable) {
                runCatching { resourceCloser.close(sandboxInstance, postmanSdk) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}

private class BaselineResourceCloser(
    private val sandboxClose: TargetCall1,
    private val sdkJsEvaluator: TargetCall1,
    private val evaluatorContext: TargetCall1,
) {
    fun close(sandbox: Any?, postmanSdk: Any?) {
        var failure: Throwable? = null

        fun attempt(closeResource: () -> Unit) {
            try {
                closeResource()
            } catch (closeFailure: Throwable) {
                failure?.addSuppressed(closeFailure) ?: run { failure = closeFailure }
            }
        }

        sandbox?.let { ownedSandbox ->
            attempt { sandboxClose.invoke(ownedSandbox) }
        }
        postmanSdk?.let { ownedSdk ->
            attempt {
                var evaluator: Any? = sdkJsEvaluator.invoke(ownedSdk)
                var context: Any? = evaluatorContext.invoke(evaluator)
                try {
                    (context as AutoCloseable).close()
                } finally {
                    context = null
                    evaluator = null
                }
            }
        }

        failure?.let { throw it }
    }
}

@JsonClass(generateAdapter = true)
internal data class BaselineRegexInputs(
    val mixedStrings: List<String>,
    val environment: Map<String, String>,
)
