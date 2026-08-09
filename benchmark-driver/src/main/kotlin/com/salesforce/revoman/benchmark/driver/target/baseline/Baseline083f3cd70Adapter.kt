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
import com.salesforce.revoman.benchmark.driver.target.TargetOperation
import com.salesforce.revoman.benchmark.driver.target.TargetRuntime
import com.salesforce.revoman.benchmark.driver.target.executionDigest
import com.squareup.moshi.JsonClass
import java.lang.invoke.MethodHandle
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
                target = target,
                collectionPath = collectionPath,
                baseUrl = request.baseUrl,
                configure = target.staticMethod(kick, "configure", builder),
                templatePath =
                    target.virtualMethod(builder, "templatePath", builder, String::class.java),
                dynamicEnvironment =
                    target.virtualMethod(
                        builder,
                        "dynamicEnvironment",
                        builder,
                        String::class.java,
                        Any::class.java,
                    ),
                insecureHttp =
                    target.virtualMethod(
                        builder,
                        "insecureHttp",
                        builder,
                        Boolean::class.javaPrimitiveType!!,
                    ),
                hooks = target.virtualMethod(builder, "hooks", builder, Iterable::class.java),
                pollingConfig =
                    target.virtualMethod(builder, "pollingConfig", builder, Iterable::class.java),
                runLogSink = target.virtualMethod(builder, "runLogSink", builder, runLogSink),
                noOpSink = target.invoke(target.fieldGetter(noOpSink, "INSTANCE", noOpSink)),
                off = target.virtualMethod(builder, "off", kick),
                revUp = target.staticMethod(revoman, "revUp", rundown, kick),
                stepReports = target.fieldGetter(rundown, "stepReports", List::class.java),
                successful =
                    target.fieldGetter(
                        stepReport,
                        "isSuccessful",
                        Boolean::class.javaPrimitiveType!!,
                    ),
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
    private const val NO_OP_RUN_LOG_SINK =
        "com.salesforce.revoman.output.log.RunLogSink\$NoOp"
}

private class BaselineLifecyclePreparedWorkload(
    private val runtime: TargetRuntime,
    private val target: ReflectiveTarget,
    private val collectionPath: String,
    private val baseUrl: String,
    private val configure: MethodHandle,
    private val templatePath: MethodHandle,
    private val dynamicEnvironment: MethodHandle,
    private val insecureHttp: MethodHandle,
    private val hooks: MethodHandle,
    private val pollingConfig: MethodHandle,
    private val runLogSink: MethodHandle,
    private var noOpSink: Any?,
    private val off: MethodHandle,
    private val revUp: MethodHandle,
    private val stepReports: MethodHandle,
    private val successful: MethodHandle,
) : PreparedWorkload {
    private var closed: Boolean = false
    private val lifecycleOperation = TargetOperation { execute().checksum }

    override fun execute(): ExecutionDigest {
        check(!closed) { "baseline-83f3cd70 prepared workload is closed" }
        return runtime.withTargetContext {
            var builder: Any? = target.invoke(configure)
            target.invoke(templatePath, builder, collectionPath)
            target.invoke(dynamicEnvironment, builder, "baseUrl", baseUrl)
            target.invoke(insecureHttp, builder, true)
            target.invoke(hooks, builder, emptyList<Any>())
            target.invoke(pollingConfig, builder, emptyList<Any>())
            target.invoke(runLogSink, builder, noOpSink)
            var kick: Any? = target.invoke(off, builder)
            builder = null
            var rundown: Any? = target.invoke(revUp, kick)
            kick = null
            var reports: List<*>? = target.invoke(stepReports, rundown) as List<*>
            rundown = null
            val executed = checkNotNull(reports).size
            val failures =
                checkNotNull(reports).count { report ->
                    !(target.invoke(successful, report) as Boolean)
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

private class BaselineComponentPreparedWorkload private constructor(
    private val runtime: TargetRuntime,
    private val target: ReflectiveTarget,
    private val regexReplace: MethodHandle,
    private val regexReplaceEnvironment: MethodHandle,
    private var regexReplacer: Any?,
    private var postmanSdk: Any?,
    private val mixedStrings: List<String>,
    private val fromJson: MethodHandle,
    private val toJson: MethodHandle,
    private val compositeType: Class<*>,
    private val typedAdapters: Map<Type, Any?>,
    private val compositeJson: String,
    private var composite: Any?,
    private val scopeConstructor: MethodHandle,
    private val contextConstructor: MethodHandle,
    private val sandboxExecute: MethodHandle,
    private val resultEnvironment: MethodHandle,
    private var sandbox: Any?,
    private var scriptTarget: Any?,
    private val script: String,
    private val sandboxClose: MethodHandle,
    private val persistentMapConstructor: MethodHandle,
    private val environmentConstructor: MethodHandle,
    private val setCurrentStep: MethodHandle,
    private val environmentSet: MethodHandle,
    private val environmentSnapshot: MethodHandle,
    private val stepName: MethodHandle,
    private var steps: List<Any?>,
    private val engineCreate: MethodHandle,
    private val engineClose: MethodHandle,
    private val sdkJsEvaluator: MethodHandle,
    private val evaluatorContext: MethodHandle,
) : PreparedWorkload {
    private var closed: Boolean = false
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
        runtime.withTargetContext {
            sandbox?.let { target.invoke(sandboxClose, it) }
            postmanSdk?.let { sdk ->
                var evaluator: Any? = target.invoke(sdkJsEvaluator, sdk)
                var context: Any? = target.invoke(evaluatorContext, evaluator)
                (context as AutoCloseable).close()
                context = null
                evaluator = null
            }
        }
        sandbox = null
        scriptTarget = null
        regexReplacer = null
        postmanSdk = null
        composite = null
        steps = emptyList()
        closed = true
    }

    private fun scalarOperation(block: () -> Long): TargetOperation =
        TargetOperation {
            check(!closed) { "baseline-83f3cd70 prepared workload is closed" }
            runtime.withTargetContext(block)
        }

    private fun sumRange(): Long = (1L..1_000L).sum()

    private fun replaceMixedStrings(): Long =
        mixedStrings.sumOf { value ->
            (target.invoke(regexReplace, requireNotNull(regexReplacer), value, postmanSdk) as String)
                .length
                .toLong()
        }

    private fun replaceLargeEnvironment(): Long {
        var result: Map<*, *>? =
            target.invoke(regexReplaceEnvironment, requireNotNull(regexReplacer), postmanSdk)
                as Map<*, *>
        val size = result!!.size.toLong()
        result = null
        return size
    }

    private fun compositeFromJson(): Long {
        var result: Any? =
            target.invoke(
                fromJson,
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
            target.invoke(
                toJson,
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
        var environment: Any? =
            target.invoke(scopeConstructor, "e", emptyMap<String, Any>(), null)
        var globals: Any? =
            target.invoke(scopeConstructor, "globals", emptyMap<String, Any>(), null)
        var collectionVariables: Any? =
            target.invoke(
                scopeConstructor,
                "collectionVariables",
                emptyMap<String, Any>(),
                null,
            )
        var context: Any? =
            target.invoke(
                contextConstructor,
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
            target.invoke(sandboxExecute, sandbox, script, scriptTarget, context, 5_000L)
        context = null
        var values: Map<*, *>? = target.invoke(resultEnvironment, result) as Map<*, *>
        result = null
        val id = (values!!["id"] as Number).toLong()
        values = null
        return id
    }

    private fun accumulateEnvironment(): Long {
        var backing: Any? = target.invoke(persistentMapConstructor)
        var environment: Any? = target.invoke(environmentConstructor, backing)
        backing = null
        var checksum = 0L
        steps.forEach { step ->
            target.invoke(setCurrentStep, environment, step)
            val name = target.invoke(stepName, step) as String
            target.invoke(environmentSet, environment, "key_$name", name)
            var snapshot: Any? = target.invoke(environmentSnapshot, environment)
            checksum += (snapshot as Map<*, *>).size
            snapshot = null
        }
        environment = null
        return checksum
    }

    private fun openGraalEngine(): Long {
        var engine: Any? = target.invoke(engineCreate)
        target.invoke(engineClose, engine)
        engine = null
        return 1
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
            val companion = target.invoke(target.fieldGetter(moshi, "Companion", moshiCompanion))
            val initMoshi =
                target.virtualMethod(
                    moshiCompanion,
                    "initMoshi\$com_salesforce_revoman_revoman",
                    moshi,
                )
            val regexConstructor = target.constructor(regex)
            val sdkConstructor =
                target.constructor(sdk, moshi, String::class.java, regex, Map::class.java)
            val regexReplacer = target.invoke(regexConstructor)
            val postmanSdk =
                target.invoke(
                    sdkConstructor,
                    target.invoke(initMoshi, companion),
                    null,
                    regexReplacer,
                    emptyMap<String, Any>(),
                )
            val sdkEnvironment = target.fieldGetter(sdk, "environment", environment)
            val environmentSet =
                target.virtualMethod(
                    environment,
                    "set",
                    Void.TYPE,
                    String::class.java,
                    Any::class.java,
                )
            regexInputs.environment.forEach { (key, value) ->
                target.invoke(
                    environmentSet,
                    target.invoke(sdkEnvironment, postmanSdk),
                    key,
                    value,
                )
            }

            val jsonUtils = target.type("com.salesforce.revoman.input.json.JsonPojoUtils")
            val compositeType =
                target.type("com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse")
            val responseType =
                target.type(
                    "com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse\$Response"
                )
            val adapterFactory = target.type("com.squareup.moshi.JsonAdapter\$Factory")
            val either = target.type("io.vavr.control.Either")
            val adapter = target.invoke(target.fieldGetter(compositeType, "ADAPTER", adapterFactory))
            val eitherRight = target.staticMethod(either, "right", either, Any::class.java)
            val typedAdapters: Map<Type, Any?> =
                mapOf(responseType to target.invoke(eitherRight, adapter))
            val fromJson =
                target.staticMethod(
                    jsonUtils,
                    "jsonToPojo",
                    Any::class.java,
                    Type::class.java,
                    String::class.java,
                    List::class.java,
                    Map::class.java,
                    Set::class.java,
                )
            val toJson =
                target.staticMethod(
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
            val composite =
                target.invoke(
                    fromJson,
                    compositeType,
                    compositeJson,
                    emptyList<Any>(),
                    typedAdapters,
                    emptySet<Class<*>>(),
                )

            val scope = target.type("com.salesforce.revoman.internal.postman.sandbox.PmScope")
            val context =
                target.type("com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext")
            val scriptTarget =
                target.type("com.salesforce.revoman.internal.postman.sandbox.ScriptTarget")
            val sandbox = target.type("com.salesforce.revoman.internal.postman.sandbox.PmSandbox")
            val sandboxResult =
                target.type("com.salesforce.revoman.internal.postman.sandbox.PmExecutionResult")
            val testTarget =
                target.invoke(target.fieldGetter(scriptTarget, "TEST", scriptTarget))
            val sandboxInstance = target.invoke(target.constructor(sandbox))

            val item = target.type("com.salesforce.revoman.internal.postman.template.Item")
            val request = target.type("com.salesforce.revoman.internal.postman.template.Request")
            val folder = target.type("com.salesforce.revoman.output.report.Folder")
            val step = target.type("com.salesforce.revoman.output.report.Step")
            val itemConstructor =
                target.constructor(
                    item,
                    String::class.java,
                    List::class.java,
                    request,
                    List::class.java,
                    String::class.java,
                )
            val requestConstructor = target.constructor(request)
            val stepConstructor =
                target.constructor(
                    step,
                    String::class.java,
                    item,
                    folder,
                    String::class.java,
                )
            val steps =
                (0 until stepCount).map { index ->
                    val targetItem =
                        target.invoke(
                            itemConstructor,
                            "s$index",
                            null,
                            target.invoke(requestConstructor),
                            null,
                            "",
                        )
                    target.invoke(stepConstructor, index.toString(), targetItem, null, "")
                }

            val persistentMap =
                target.type("com.salesforce.revoman.output.postman.PersistentBackedMutableMap")
            val engine = target.type("org.graalvm.polyglot.Engine")
            val evaluator =
                target.type("com.salesforce.revoman.internal.postman.PostmanSDK\$JSEvaluator")
            return BaselineComponentPreparedWorkload(
                runtime = runtime,
                target = target,
                regexReplace =
                    target.virtualMethod(
                        regex,
                        "replaceVariablesRecursively\$com_salesforce_revoman_revoman",
                        String::class.java,
                        String::class.java,
                        sdk,
                    ),
                regexReplaceEnvironment =
                    target.virtualMethod(
                        regex,
                        "replaceVariablesInEnv\$com_salesforce_revoman_revoman",
                        Map::class.java,
                        sdk,
                    ),
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
                    target.constructor(scope, String::class.java, Map::class.java, String::class.java),
                contextConstructor =
                    target.constructor(
                        context,
                        scope,
                        scope,
                        scope,
                        Map::class.java,
                        Map::class.java,
                    ),
                sandboxExecute =
                    target.virtualMethod(
                        sandbox,
                        "execute",
                        sandboxResult,
                        String::class.java,
                        scriptTarget,
                        context,
                        Long::class.javaPrimitiveType!!,
                    ),
                resultEnvironment =
                    target.virtualMethod(sandboxResult, "getEnvironment", Map::class.java),
                sandbox = sandboxInstance,
                scriptTarget = testTarget,
                script = script,
                sandboxClose = target.virtualMethod(sandbox, "close", Void.TYPE),
                persistentMapConstructor = target.constructor(persistentMap),
                environmentConstructor = target.constructor(environment, Map::class.java),
                setCurrentStep =
                    target.virtualMethod(
                        environment,
                        "setCurrentStep\$com_salesforce_revoman_revoman",
                        Void.TYPE,
                        step,
                    ),
                environmentSet = environmentSet,
                environmentSnapshot =
                    target.virtualMethod(environment, "o1Snapshot", environment),
                stepName = target.fieldGetter(step, "name", String::class.java),
                steps = steps,
                engineCreate = target.staticMethod(engine, "create", engine),
                engineClose = target.virtualMethod(engine, "close", Void.TYPE),
                sdkJsEvaluator = target.fieldGetter(sdk, "jsEvaluator"),
                evaluatorContext = target.fieldGetter(evaluator, "jsContext"),
            )
        }

    }
}

@JsonClass(generateAdapter = true)
internal data class BaselineRegexInputs(
    val mixedStrings: List<String>,
    val environment: Map<String, String>,
)
