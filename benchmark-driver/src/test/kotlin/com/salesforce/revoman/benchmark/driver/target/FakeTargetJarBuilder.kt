/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.target

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.HashedArtifact
import com.salesforce.revoman.benchmark.driver.model.JdkIdentity
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

/** Compiles deterministic Java-only target surfaces for real classloader/reflection tests. */
class FakeTargetJarBuilder(private val root: Path) {
    private var buildNumber: Int = 0

    fun runtimeJar(): Path =
        buildJar(
            "runtime",
            mapOf(
                "fake.target.First" to "package fake.target; public final class First {}",
                "fake.target.Second" to "package fake.target; public final class Second {}",
            ),
        )

    fun baselineJar(): Path =
        buildJar(
            "baseline",
            mapOf(
                "com.salesforce.revoman.input.config.Kick" to BASELINE_KICK,
                "com.salesforce.revoman.output.log.RunLogSink" to BASELINE_RUN_LOG_SINK,
                "com.salesforce.revoman.output.report.StepReport" to BASELINE_STEP_REPORT,
                "com.salesforce.revoman.output.Rundown" to BASELINE_RUNDOWN,
                "com.salesforce.revoman.ReVoman" to BASELINE_REVOMAN,
            ),
        )

    fun majorJar(diagnostics: MajorDiagnosticsFixture? = null): Path =
        buildJar(
            "major",
            buildMap {
                put("com.salesforce.revoman.input.config.Kick", MAJOR_KICK)
                put("com.salesforce.revoman.output.Rundown", MAJOR_RUNDOWN)
                put(
                    "com.salesforce.revoman.ReVoman",
                    if (diagnostics == null) MAJOR_REVOMAN else MAJOR_REVOMAN_WITH_DIAGNOSTICS,
                )
                diagnostics?.let { fixture ->
                    put(
                        "com.salesforce.revoman.internal.runtime.ExecutionLifecycleDiagnostics",
                        fixture.source,
                    )
                }
            },
        )

    fun componentJar(failPreparationAfterResources: Boolean = false): Path =
        buildJar(
            "components",
            mapOf(
                "com.salesforce.revoman.internal.json.MoshiReVoman" to COMPONENT_MOSHI,
                "com.salesforce.revoman.internal.postman.RegexReplacer" to COMPONENT_REGEX,
                "com.salesforce.revoman.internal.postman.PostmanSDK" to COMPONENT_POSTMAN_SDK,
                "com.squareup.moshi.JsonAdapter" to COMPONENT_JSON_ADAPTER,
                "io.vavr.control.Either" to COMPONENT_EITHER,
                "com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse" to
                    COMPONENT_COMPOSITE_RESPONSE,
                "com.salesforce.revoman.input.json.JsonPojoUtils" to COMPONENT_JSON_UTILS,
                "com.salesforce.revoman.internal.postman.sandbox.PmScope" to COMPONENT_PM_SCOPE,
                "com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext" to
                    COMPONENT_PM_CONTEXT,
                "com.salesforce.revoman.internal.postman.sandbox.ScriptTarget" to
                    COMPONENT_SCRIPT_TARGET,
                "com.salesforce.revoman.internal.postman.sandbox.PmExecutionResult" to
                    COMPONENT_PM_RESULT,
                "com.salesforce.revoman.internal.postman.sandbox.PmSandbox" to COMPONENT_PM_SANDBOX,
                "com.salesforce.revoman.internal.postman.template.Item" to COMPONENT_ITEM,
                "com.salesforce.revoman.internal.postman.template.Request" to COMPONENT_REQUEST,
                "com.salesforce.revoman.output.report.Folder" to COMPONENT_FOLDER,
                "com.salesforce.revoman.output.report.Step" to COMPONENT_STEP,
                "com.salesforce.revoman.output.postman.PersistentBackedMutableMap" to
                    COMPONENT_PERSISTENT_MAP,
                "com.salesforce.revoman.output.postman.PostmanEnvironment" to COMPONENT_ENVIRONMENT,
                "org.graalvm.polyglot.Engine" to
                    when {
                        failPreparationAfterResources -> COMPONENT_ENGINE_WITHOUT_CREATE
                        else -> COMPONENT_ENGINE
                    },
                "org.graalvm.polyglot.Context" to COMPONENT_CONTEXT,
            ),
        )

    fun manifestFor(jar: Path, targetId: String = jar.fileName.toString()): Path {
        val canonicalJar = jar.toRealPath()
        val manifest =
            TargetManifest(
                targetId = targetId,
                gitCommit = "1".repeat(40),
                gitTree = "2".repeat(40),
                dirty = false,
                gradleVersion = "9.1.0",
                wrapperSha256 = "3".repeat(64),
                jdk =
                    JdkIdentity(
                        distribution = "test",
                        vendor = "test",
                        fullVersion = System.getProperty("java.version"),
                        javaHome = Path.of(System.getProperty("java.home")).toRealPath().toString(),
                        jvmFlags = emptyList(),
                    ),
                classpath =
                    listOf(
                        HashedArtifact(
                            logicalId = "target.jar",
                            executionPath = canonicalJar.toString(),
                            sizeBytes = Files.size(canonicalJar),
                            sha256 = ContentHasher.sha256(canonicalJar),
                        )
                    ),
            )
        val manifestPath = root.resolve("$targetId-manifest-${buildNumber++}.json")
        BenchmarkJson.write(manifestPath, manifest)
        return manifestPath.toRealPath()
    }

    private fun buildJar(name: String, sources: Map<String, String>): Path {
        val buildRoot = Files.createDirectory(root.resolve("$name-${buildNumber++}"))
        val sourceRoot = Files.createDirectory(buildRoot.resolve("src"))
        val classesRoot = Files.createDirectory(buildRoot.resolve("classes"))
        val sourcePaths = sources.map { (className, source) ->
            sourceRoot.resolve(className.replace('.', '/') + ".java").also {
                Files.createDirectories(requireNotNull(it.parent))
                Files.writeString(it, source)
            }
        }

        val compiler =
            requireNotNull(ToolProvider.getSystemJavaCompiler()) { "Tests require a JDK" }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { fileManager ->
            val compilationUnits = fileManager.getJavaFileObjectsFromPaths(sourcePaths)
            val compiled =
                compiler
                    .getTask(
                        null,
                        fileManager,
                        diagnostics,
                        listOf("--release", "21", "-d", classesRoot.toString()),
                        null,
                        compilationUnits,
                    )
                    .call()
            check(compiled) {
                diagnostics.diagnostics.joinToString(System.lineSeparator()) { it.toString() }
            }
        }

        val jar = buildRoot.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(classesRoot).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { classFile ->
                    val entryName = classesRoot.relativize(classFile).joinToString("/")
                    output.putNextEntry(JarEntry(entryName).apply { time = 0 })
                    Files.copy(classFile, output)
                    output.closeEntry()
                }
            }
        }
        return jar.toRealPath()
    }

    private companion object {
        val BASELINE_KICK =
            """
            package com.salesforce.revoman.input.config;

            import com.salesforce.revoman.output.log.RunLogSink;
            import java.util.Map;

            public final class Kick {
                public final String templatePath;
                public final String baseUrl;
                public final boolean insecureHttp;
                public final boolean hooksBound;
                public final boolean pollingBound;
                public final boolean sinkBound;

                private Kick(Builder builder) {
                    templatePath = builder.templatePath;
                    baseUrl = builder.baseUrl;
                    insecureHttp = builder.insecureHttp;
                    hooksBound = builder.hooksBound;
                    pollingBound = builder.pollingBound;
                    sinkBound = builder.sinkBound;
                }

                public static Builder configure() { return new Builder(); }

                public static final class Builder {
                    private String templatePath;
                    private String baseUrl;
                    private boolean insecureHttp;
                    private boolean hooksBound;
                    private boolean pollingBound;
                    private boolean sinkBound;

                    public Builder templatePath(String value) { templatePath = value; return this; }
                    public Builder dynamicEnvironment(String key, Object value) {
                        if ("baseUrl".equals(key)) baseUrl = String.valueOf(value);
                        return this;
                    }
                    public Builder insecureHttp(boolean value) { insecureHttp = value; return this; }
                    public Builder hooks(Iterable<?> value) { hooksBound = value != null; return this; }
                    public Builder pollingConfig(Iterable<?> value) { pollingBound = value != null; return this; }
                    public Builder runLogSink(RunLogSink value) {
                        sinkBound = value == RunLogSink.NoOp.INSTANCE;
                        return this;
                    }
                    public Kick off() { return new Kick(this); }
                }
            }
            """
                .trimIndent()

        val BASELINE_RUN_LOG_SINK =
            """
            package com.salesforce.revoman.output.log;

            public interface RunLogSink extends AutoCloseable {
                @Override void close();

                final class NoOp implements RunLogSink {
                    public static final NoOp INSTANCE = new NoOp();
                    private NoOp() {}
                    @Override public void close() {}
                }
            }
            """
                .trimIndent()

        val BASELINE_STEP_REPORT =
            """
            package com.salesforce.revoman.output.report;

            public final class StepReport {
                public final boolean isSuccessful;
                public StepReport(boolean isSuccessful) { this.isSuccessful = isSuccessful; }
            }
            """
                .trimIndent()

        val BASELINE_RUNDOWN =
            """
            package com.salesforce.revoman.output;

            import com.salesforce.revoman.output.report.StepReport;
            import java.util.List;

            public final class Rundown {
                public final List<StepReport> stepReports;
                public Rundown(List<StepReport> stepReports) { this.stepReports = stepReports; }
            }
            """
                .trimIndent()

        val BASELINE_REVOMAN =
            """
            package com.salesforce.revoman;

            import com.salesforce.revoman.input.config.Kick;
            import com.salesforce.revoman.output.Rundown;
            import com.salesforce.revoman.output.report.StepReport;
            import java.util.List;

            public final class ReVoman {
                public static Rundown revUp(Kick kick) {
                    if (Thread.currentThread().getContextClassLoader() != ReVoman.class.getClassLoader()) {
                        throw new IllegalStateException("target context classloader not installed");
                    }
                    if (kick.templatePath == null || kick.baseUrl == null || !kick.insecureHttp
                            || !kick.hooksBound || !kick.pollingBound || !kick.sinkBound) {
                        throw new IllegalStateException("baseline lifecycle binding incomplete");
                    }
                    return new Rundown(List.of(new StepReport(true)));
                }
            }
            """
                .trimIndent()

        val MAJOR_KICK =
            """
            package com.salesforce.revoman.input.config;

            public final class Kick {
                public final String templatePath;
                public final String baseUrl;
                public final boolean insecureHttp;

                private Kick(Builder builder) {
                    templatePath = builder.templatePath;
                    baseUrl = builder.baseUrl;
                    insecureHttp = builder.insecureHttp;
                }

                public static Builder configure() { return new Builder(); }

                public static final class Builder {
                    private String templatePath;
                    private String baseUrl;
                    private boolean insecureHttp;

                    public Builder templatePath(String value) { templatePath = value; return this; }
                    public Builder dynamicEnvironment(String key, Object value) {
                        if ("baseUrl".equals(key)) baseUrl = String.valueOf(value);
                        return this;
                    }
                    public Builder insecureHttp(boolean value) { insecureHttp = value; return this; }
                    public Kick off() { return new Kick(this); }
                }
            }
            """
                .trimIndent()

        val MAJOR_RUNDOWN =
            """
            package com.salesforce.revoman.output;

            public final class Rundown {
                private final int executed;
                private final int unsuccessful;

                public Rundown(int executed, int unsuccessful) {
                    this.executed = executed;
                    this.unsuccessful = unsuccessful;
                }

                public int executedStepCount() { return executed; }
                public int unsuccessfulStepCount() { return unsuccessful; }
            }
            """
                .trimIndent()

        val MAJOR_REVOMAN =
            """
            package com.salesforce.revoman;

            import com.salesforce.revoman.input.config.Kick;
            import com.salesforce.revoman.output.Rundown;

            public final class ReVoman {
                public static Rundown revUp(Kick kick) {
                    if (Thread.currentThread().getContextClassLoader() != ReVoman.class.getClassLoader()) {
                        throw new IllegalStateException("target context classloader not installed");
                    }
                    if (kick.templatePath == null || kick.baseUrl == null || !kick.insecureHttp) {
                        throw new IllegalStateException("major lifecycle binding incomplete");
                    }
                    return new Rundown(1, 0);
                }
            }
            """
                .trimIndent()

        val MAJOR_REVOMAN_WITH_DIAGNOSTICS =
            """
            package com.salesforce.revoman;

            import com.salesforce.revoman.input.config.Kick;
            import com.salesforce.revoman.internal.runtime.ExecutionLifecycleDiagnostics;
            import com.salesforce.revoman.output.Rundown;

            public final class ReVoman {
                public static Rundown revUp(Kick kick) {
                    if (Thread.currentThread().getContextClassLoader() != ReVoman.class.getClassLoader()) {
                        throw new IllegalStateException("target context classloader not installed");
                    }
                    if (kick.templatePath == null || kick.baseUrl == null || !kick.insecureHttp) {
                        throw new IllegalStateException("major lifecycle binding incomplete");
                    }
                    ExecutionLifecycleDiagnostics.registerExecution();
                    return new Rundown(1, 0);
                }
            }
            """
                .trimIndent()

        val COMPONENT_MOSHI =
            """
            package com.salesforce.revoman.internal.json;

            public final class MoshiReVoman {
                public static final Companion Companion = new Companion();
                public static final class Companion {
                    public MoshiReVoman initMoshi${'$'}com_salesforce_revoman_revoman() {
                        return new MoshiReVoman();
                    }
                }
            }
            """
                .trimIndent()

        val COMPONENT_REGEX =
            """
            package com.salesforce.revoman.internal.postman;

            import java.util.LinkedHashMap;
            import java.util.Map;

            public final class RegexReplacer {
                public RegexReplacer() {}
                public String replaceVariablesRecursively${'$'}com_salesforce_revoman_revoman(
                        String value, PostmanSDK sdk) {
                    return value.replace("{{policyId}}", String.valueOf(sdk.environment.get("policyId")));
                }
                public Map<String, Object> replaceVariablesInEnv${'$'}com_salesforce_revoman_revoman(
                        PostmanSDK sdk) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    sdk.environment.forEach((key, value) -> result.put(
                            key.replace("{{policyId}}", String.valueOf(sdk.environment.get("policyId"))),
                            value instanceof String
                                    ? ((String) value).replace("{{policyId}}", String.valueOf(sdk.environment.get("policyId")))
                                    : value));
                    return result;
                }
            }
            """
                .trimIndent()

        val COMPONENT_POSTMAN_SDK =
            """
            package com.salesforce.revoman.internal.postman;

            import com.salesforce.revoman.internal.json.MoshiReVoman;
            import com.salesforce.revoman.output.postman.PostmanEnvironment;
            import java.util.LinkedHashMap;
            import java.util.Map;

            public final class PostmanSDK {
                public final PostmanEnvironment<Object> environment;
                private final JSEvaluator jsEvaluator = new JSEvaluator();

                public PostmanSDK(MoshiReVoman moshi, String modules, RegexReplacer regex, Map<String, Object> env) {
                    environment = new PostmanEnvironment<>(env);
                }

                public static final class JSEvaluator {
                    private final org.graalvm.polyglot.Context jsContext = new org.graalvm.polyglot.Context();
                }
            }
            """
                .trimIndent()

        val COMPONENT_JSON_ADAPTER =
            """
            package com.squareup.moshi;

            public class JsonAdapter<T> {
                public interface Factory {}
            }
            """
                .trimIndent()

        val COMPONENT_EITHER =
            """
            package io.vavr.control;

            public final class Either<L, R> {
                private final R value;
                private Either(R value) { this.value = value; }
                public static <L, R> Either<L, R> right(R value) { return new Either<>(value); }
            }
            """
                .trimIndent()

        val COMPONENT_COMPOSITE_RESPONSE =
            """
            package com.salesforce.revoman.input.json.adapters.salesforce;

            import com.squareup.moshi.JsonAdapter;

            public final class CompositeResponse {
                public static final JsonAdapter.Factory ADAPTER = new JsonAdapter.Factory() {};
                private final String json;
                public CompositeResponse(String json) { this.json = json; }
                public String json() { return json; }
                public interface Response {}
            }
            """
                .trimIndent()

        val COMPONENT_JSON_UTILS =
            """
            package com.salesforce.revoman.input.json;

            import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse;
            import java.lang.reflect.Type;
            import java.util.List;
            import java.util.Map;
            import java.util.Set;

            public final class JsonPojoUtils {
                public static Object jsonToPojo(
                        Type type, String json, List<?> adapters, Map<?, ?> typedAdapters, Set<?> skipped) {
                    if (typedAdapters.isEmpty()) throw new IllegalStateException("typed adapter missing");
                    return new CompositeResponse(json);
                }
                public static String pojoToJson(
                        Type type, Object value, List<?> adapters, Map<?, ?> typedAdapters,
                        Set<?> skipped, String indent) {
                    return ((CompositeResponse) value).json();
                }
            }
            """
                .trimIndent()

        val COMPONENT_PM_SCOPE =
            """
            package com.salesforce.revoman.internal.postman.sandbox;

            import java.util.Map;

            public final class PmScope {
                public PmScope(String id, Map<String, Object> values, String name) {}
            }
            """
                .trimIndent()

        val COMPONENT_PM_CONTEXT =
            """
            package com.salesforce.revoman.internal.postman.sandbox;

            import java.util.Map;

            public final class PmExecutionContext {
                public PmExecutionContext(
                        PmScope environment, PmScope globals, PmScope collectionVariables,
                        Map<String, Object> request, Map<String, Object> response) {}
            }
            """
                .trimIndent()

        val COMPONENT_SCRIPT_TARGET =
            """
            package com.salesforce.revoman.internal.postman.sandbox;

            public enum ScriptTarget { PRE_REQUEST, TEST }
            """
                .trimIndent()

        val COMPONENT_PM_RESULT =
            """
            package com.salesforce.revoman.internal.postman.sandbox;

            import java.util.Map;

            public final class PmExecutionResult {
                private final Map<String, Object> environment;
                public PmExecutionResult(Map<String, Object> environment) { this.environment = environment; }
                public Map<String, Object> getEnvironment() { return environment; }
            }
            """
                .trimIndent()

        val COMPONENT_PM_SANDBOX =
            """
            package com.salesforce.revoman.internal.postman.sandbox;

            import java.util.Map;

            public final class PmSandbox implements AutoCloseable {
                public static int closedCount;
                public static boolean failClose;
                public PmSandbox() {}
                public PmExecutionResult execute(
                        String script, ScriptTarget target, PmExecutionContext context, long timeout) {
                    return new PmExecutionResult(Map.of("id", 42));
                }
                @Override public void close() {
                    closedCount++;
                    if (failClose) throw new IllegalStateException("sandbox close failed");
                }
            }
            """
                .trimIndent()

        val COMPONENT_ITEM =
            """
            package com.salesforce.revoman.internal.postman.template;

            import java.util.List;

            public final class Item {
                public final String name;
                public Item() { this("", null, new Request(), null, ""); }
                public Item(String name, List<Item> items, Request request, List<Object> events, String sourceHash) {
                    this.name = name;
                }
            }
            """
                .trimIndent()

        val COMPONENT_REQUEST =
            """
            package com.salesforce.revoman.internal.postman.template;
            public final class Request { public Request() {} }
            """
                .trimIndent()

        val COMPONENT_FOLDER =
            """
            package com.salesforce.revoman.output.report;
            public final class Folder {}
            """
                .trimIndent()

        val COMPONENT_STEP =
            """
            package com.salesforce.revoman.output.report;

            import com.salesforce.revoman.internal.postman.template.Item;

            public final class Step {
                public final String index;
                public final String name;
                public Step(String index, Item item, Folder folder, String sourceHash) {
                    this.index = index;
                    this.name = item.name;
                }
            }
            """
                .trimIndent()

        val COMPONENT_PERSISTENT_MAP =
            """
            package com.salesforce.revoman.output.postman;

            import java.util.LinkedHashMap;

            public final class PersistentBackedMutableMap<V> extends LinkedHashMap<String, V> {
                public PersistentBackedMutableMap() {}
            }
            """
                .trimIndent()

        val COMPONENT_ENVIRONMENT =
            """
            package com.salesforce.revoman.output.postman;

            import com.salesforce.revoman.output.report.Step;
            import java.util.AbstractMap;
            import java.util.Map;
            import java.util.Set;

            public final class PostmanEnvironment<V> extends AbstractMap<String, V> {
                private final Map<String, V> values;
                private Step currentStep;
                public PostmanEnvironment(Map<String, V> values) { this.values = values; }
                @Override public Set<Entry<String, V>> entrySet() { return values.entrySet(); }
                @Override public V put(String key, V value) { return values.put(key, value); }
                @Override public V remove(Object key) { return values.remove(key); }
                @Override public int size() { return values.size(); }
                public void setCurrentStep${'$'}com_salesforce_revoman_revoman(Step step) { currentStep = step; }
                public void set(String key, V value) {
                    if (currentStep != null && !key.equals("key_" + currentStep.name)) {
                        throw new IllegalStateException("environment key does not match current step");
                    }
                    put(key, value);
                }
                public PostmanEnvironment<V> o1Snapshot() {
                    return new PostmanEnvironment<>(new java.util.LinkedHashMap<>(values));
                }
            }
            """
                .trimIndent()

        val COMPONENT_ENGINE =
            """
            package org.graalvm.polyglot;

            public final class Engine implements AutoCloseable {
                public static int opened;
                public static int closed;
                public static boolean failClose;
                public static Engine create() { opened++; return new Engine(); }
                @Override public void close() {
                    closed++;
                    if (failClose) throw new IllegalStateException("engine close failed");
                }
            }
            """
                .trimIndent()

        val COMPONENT_ENGINE_WITHOUT_CREATE =
            """
            package org.graalvm.polyglot;

            public final class Engine implements AutoCloseable {
                @Override public void close() {}
            }
            """
                .trimIndent()

        val COMPONENT_CONTEXT =
            """
            package org.graalvm.polyglot;

            public final class Context implements AutoCloseable {
                public static int closed;
                public static boolean failClose;
                @Override public void close() {
                    closed++;
                    if (failClose) throw new IllegalStateException("context close failed");
                }
            }
            """
                .trimIndent()
    }
}

enum class MajorDiagnosticsFixture(internal val source: String) {
    VALID(
        diagnosticsSource(
            """
            Object[] result = records.toArray(new Object[0]);
            records = new ArrayList<>();
            return result;
            """
                .trimIndent()
        )
    ),
    EMPTY(diagnosticsSource("return new Object[0];")),
    ODD(diagnosticsSource("return new Object[] { \"ExecutionSession\" };")),
    NON_STRING(diagnosticsSource("return new Object[] { 1, new WeakReference<>(new Object()) };")),
    BLANK(diagnosticsSource("return new Object[] { \" \", new WeakReference<>(new Object()) };")),
    UNKNOWN(diagnosticsSource("return new Object[] { \"Unknown\", new WeakReference<>(new Object()) };")),
    WEAK_SUBCLASS(
        diagnosticsSource(
            "return new Object[] { \"ExecutionSession\", new CustomWeakReference(new Object()) };",
            "static final class CustomWeakReference extends WeakReference<Object> { CustomWeakReference(Object value) { super(value); } }",
        )
    ),
    REPEATED_REFERENCE(
        diagnosticsSource(
            "WeakReference<Object> reference = new WeakReference<>(new Object()); return new Object[] { \"ExecutionSession\", reference, \"KickExecution\", reference };"
        )
    ),
}

private fun diagnosticsSource(drainBody: String, extraBody: String = ""): String =
    """
    package com.salesforce.revoman.internal.runtime;

    import java.lang.ref.WeakReference;
    import java.util.ArrayList;

    public final class ExecutionLifecycleDiagnostics {
        private static ArrayList<Object> records = new ArrayList<>();
        private static ArrayList<Object> owners = new ArrayList<>();

        public static void registerExecution() {
            Object session = new ExecutionSessionToken();
            Object kick = new KickExecutionToken();
            owners.add(session);
            owners.add(kick);
            records.add(new String("ExecutionSession"));
            records.add(new WeakReference<>(session));
            records.add(new String("KickExecution"));
            records.add(new WeakReference<>(kick));
        }

        public static Object[] drain() {
            $drainBody
        }

        static final class ExecutionSessionToken {}
        static final class KickExecutionToken {}
        $extraBody
    }
    """
        .trimIndent()
