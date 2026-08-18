/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.support

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider
import performance.distribution.DistributionValidationRequest
import performance.distribution.JavaRuntimeIdentity
import performance.hash.Sha256
import performance.json.CanonicalJson
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

internal class DistributionFixture private constructor(
  val root: Path,
  val selectedJava: JavaRuntimeIdentity,
) {
  val benchmarkClasspath: List<String> =
    listOf(
      BENCHMARK_JAR,
      PRODUCTION_JAR,
      BENCHMARK_DEPENDENCY,
    )

  val runnerClasspath: List<String> =
    listOf(
      RUNNER_JAR,
      RUNNER_DEPENDENCY,
    )

  val stagingOutput: Path = root.resolveSibling("staging")

  fun request(
    selectedJava: JavaRuntimeIdentity = this.selectedJava,
    expectedProtocolHash: Sha256? = protocolHash(),
    stagingOutput: Path? = this.stagingOutput,
  ): DistributionValidationRequest =
    DistributionValidationRequest(
      root = root,
      selectedJava = selectedJava,
      expectedProtocolHash = expectedProtocolHash,
      stagingOutput = stagingOutput,
    )

  fun mutateClasspath(mutation: (ObjectNode) -> Unit) {
    mutateJson(CLASSPATH_MANIFEST, mutation)
  }

  fun mutateProvenance(mutation: (ObjectNode) -> Unit) {
    mutateJson(PROVENANCE_MANIFEST, mutation)
  }

  fun mutateProtocol(mutation: (ObjectNode) -> Unit) {
    mutateJson(PROTOCOL_MANIFEST, mutation)
  }

  fun declareJava(identity: JavaRuntimeIdentity) {
    mutateClasspath { document ->
      document.get("javaRuntime").asObject().apply {
        put("executable", identity.executable.toString())
        put("executableSha256", identity.sha256.hex)
        put("featureVersion", identity.featureVersion)
      }
    }
  }

  fun replaceJar(
    relativePath: String,
    entries: Map<String, ByteArray>,
    multiRelease: Boolean = false,
  ) {
    writeJar(root.resolve(relativePath), entries, multiRelease)
    refreshArtifactHashes(relativePath)
    reseal()
  }

  fun replaceFile(relativePath: String, bytes: ByteArray, refreshBindings: Boolean = false) {
    write(relativePath, bytes)
    if (refreshBindings) {
      refreshArtifactHashes(relativePath)
    }
    reseal()
  }

  fun writeWithoutResealing(relativePath: String, bytes: ByteArray) {
    write(relativePath, bytes)
  }

  fun addFileAndReseal(relativePath: String, bytes: ByteArray) {
    write(relativePath, bytes)
    reseal()
  }

  fun deleteAndReseal(relativePath: String) {
    Files.delete(root.resolve(relativePath))
    reseal()
  }

  fun delete(relativePath: String) {
    Files.delete(root.resolve(relativePath))
  }

  fun addBenchmarkJar(
    relativePath: String,
    coordinate: String,
    entries: Map<String, ByteArray>,
    multiRelease: Boolean = false,
  ) {
    writeJar(root.resolve(relativePath), entries, multiRelease)
    mutateClasspath { document ->
      val classpath = document.arrayNode("benchmarkClasspath")
      classpath.add(classpathEntry(classpath.size(), relativePath, coordinate))
    }
  }

  fun addRunnerJar(relativePath: String, coordinate: String, source: Path) {
    val target = root.resolve(relativePath)
    Files.createDirectories(target.parent)
    Files.copy(source, target)
    mutateClasspath { document ->
      val classpath = document.arrayNode("runnerClasspath")
      classpath.add(classpathEntry(classpath.size(), relativePath, coordinate))
    }
  }

  fun setChecksumManifest(lines: List<String>) {
    Files.writeString(
      root.resolve(CHECKSUM_MANIFEST),
      lines.joinToString(separator = "\n", postfix = "\n"),
    )
  }

  fun checksumLines(): List<String> = Files.readAllLines(root.resolve(CHECKSUM_MANIFEST))

  fun prepareComparisonProtocol(multiCell: Boolean = false) {
    replaceJar(
      BENCHMARK_JAR,
      mapOf(
        "META-INF/BenchmarkList" to benchmarkList(COMPARISON_BENCHMARK).encodeToByteArray(),
        "META-INF/CompilerHints" to
          "dontinline,$COMPARISON_BENCHMARK\n".encodeToByteArray(),
        "META-INF/revoman/performance/revup-v3-tree.json" to
          "{\"schemaVersion\":\"revup-v3-tree-v1\"}".encodeToByteArray(),
        "com/salesforce/revoman/benchmark/RevUpV3WarmBenchmark.class" to
          compiledClass(
            "com.salesforce.revoman.benchmark.RevUpV3WarmBenchmark",
            "public void revUp() {}",
          ),
      ),
    )
    mutateClasspath { document ->
      document.arrayNode("expectedBenchmarks").removeAll().add(COMPARISON_BENCHMARK)
    }
    replaceFile(
      "protocol/expected-cells.json",
      comparisonExpectedCells(multiCell),
      refreshBindings = true,
    )
    replaceFile("protocol/profiles/canary.json", comparisonProfile("canary"), refreshBindings = true)
    replaceFile("protocol/profiles/cold.json", comparisonProfile("cold"), refreshBindings = true)
    replaceFile("protocol/profiles/warm.json", comparisonProfile("warm"), refreshBindings = true)
    replaceFile(
      "protocol/runtime/linux-arm64.json",
      comparisonRuntimeProfile(),
      refreshBindings = true,
    )
    replaceFile(
      "protocol/runtime/m4max-docker.json",
      comparisonSubstrateProfile("m4max-docker-linux-arm64-v1", 10001),
      refreshBindings = true,
    )
    replaceFile(
      "protocol/runtime/github-hosted.json",
      comparisonSubstrateProfile("github-hosted-arm64-v1", 1001),
      refreshBindings = true,
    )
    replaceFile(
      "protocol/qualification/m4max-docker.json",
      "{\"policy\":\"comparison-fixture\"}\n".encodeToByteArray(),
      refreshBindings = true,
    )
    replaceFile(
      "protocol/test-vectors/bootstrap-v1.json",
      checkNotNull(
          DistributionFixture::class.java.getResourceAsStream(
            "/performance/protocol/test-vectors/bootstrap-v1.json",
          ),
        ) {
          "missing bootstrap vector"
        }
        .use { it.readAllBytes() },
      refreshBindings = true,
    )
  }

  fun reseal() {
    val checksumPath = root.resolve(CHECKSUM_MANIFEST)
    val lines =
      Files.walk(root).use { paths ->
        paths
          .filter(Files::isRegularFile)
          .map(root::relativize)
          .map(::portablePath)
          .filter { it != CHECKSUM_MANIFEST }
          .sorted()
          .map { relative -> "${digest(relative).hex}  $relative" }
          .toList()
      }
    Files.createDirectories(checksumPath.parent)
    Files.writeString(checksumPath, lines.joinToString(separator = "\n", postfix = "\n"))
  }

  fun protocolHash(): Sha256 {
    val document = jsonObject(PROTOCOL_MANIFEST)
    return Sha256.parse(document.get("protocolSha256").asString())
  }

  fun close() {
    root.parent.toFile().deleteRecursively()
  }

  private fun mutateJson(relativePath: String, mutation: (ObjectNode) -> Unit) {
    val document = jsonObject(relativePath)
    mutation(document)
    write(relativePath, CanonicalJson.encode(document))
    reseal()
  }

  private fun refreshArtifactHashes(relativePath: String) {
    val digest = digest(relativePath).hex
    val classpath = jsonObject(CLASSPATH_MANIFEST)
    sequenceOf(
        classpath.arrayNode("benchmarkClasspath"),
        classpath.arrayNode("runnerClasspath"),
      )
      .flatMap { it.values().asSequence() }
      .map { it as ObjectNode }
      .filter { it.get("path").asString() == relativePath }
      .forEach {
        it.put("byteLength", Files.size(root.resolve(relativePath)))
        it.put("sha256", digest)
      }
    write(CLASSPATH_MANIFEST, CanonicalJson.encode(classpath))

    val protocol = jsonObject(PROTOCOL_MANIFEST)
    protocolArtifactObjects(protocol)
      .filter { it.get("path").asString() == relativePath }
      .forEach { it.put("sha256", digest) }
    refreshProtocolHash(protocol)
    write(PROTOCOL_MANIFEST, CanonicalJson.encode(protocol))
  }

  private fun refreshProtocolHash(protocol: ObjectNode) {
    val hash =
      Sha256.digest(
        buildList {
            addAll(
              protocolArtifactObjects(protocol).map { binding ->
                "artifact\t${binding.get("sha256").asString()}\t${binding.get("path").asString()}"
              },
            )
            addAll(
              protocol.arrayNode("sourceClosure").values().asSequence().map { binding ->
                "source\t${binding.get("sha256").asString()}\t${binding.get("path").asString()}"
              },
            )
            addAll(
              protocol
                .get("toolIdentities")
                .asObject()
                .properties()
                .map { (key, value) -> "identity\t$key\t${value.asString()}" },
            )
          }
          .sorted()
          .joinToString(separator = "\n", postfix = "\n")
          .encodeToByteArray(),
      )
    protocol.put("protocolSha256", hash.hex)
  }

  private fun jsonObject(relativePath: String): ObjectNode =
    CanonicalJson.parseStrict(Files.readAllBytes(root.resolve(relativePath))) as ObjectNode

  private fun digest(relativePath: String): Sha256 =
    Sha256.digest(Files.readAllBytes(root.resolve(relativePath)))

  private fun write(relativePath: String, bytes: ByteArray) {
    root.resolve(relativePath).also { path ->
      Files.createDirectories(path.parent)
      Files.write(path, bytes)
    }
  }

  private fun classpathEntry(
    order: Int,
    path: String,
    coordinate: String,
  ): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("byteLength", Files.size(root.resolve(path)))
      put("coordinate", coordinate)
      put("order", order)
      put("path", path)
      put("sha256", digest(path).hex)
    }

  companion object {
    const val PRODUCTION_JAR = "app/revoman.jar"
    const val BENCHMARK_JAR = "benchmark/revoman-jmh.jar"
    const val BENCHMARK_DEPENDENCY = "lib/benchmark-dependency.jar"
    const val RUNNER_JAR = "runner/performance-runner.jar"
    const val RUNNER_DEPENDENCY = "runner/lib/runner-dependency.jar"
    const val UNIX_LAUNCHER = "bin/performance-runner"
    const val WINDOWS_LAUNCHER = "bin/performance-runner.bat"
    const val CLASSPATH_MANIFEST = "metadata/classpath.json"
    const val PROVENANCE_MANIFEST = "metadata/provenance.json"
    const val PROTOCOL_MANIFEST = "metadata/protocol.json"
    const val CHECKSUM_MANIFEST = "metadata/distribution.sha256"
    const val EXPECTED_BENCHMARK = "example.Benchmark.measure"
    const val COMPARISON_BENCHMARK =
      "com.salesforce.revoman.benchmark.RevUpV3WarmBenchmark.revUp"

    private const val SHA =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private const val FIXTURE_MANIFEST_DIGEST =
      "sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e"
    private const val FIXTURE_CONFIG_DIGEST =
      "sha256:ad6963934ee96838c09d99f3c4df6f991cd00ed70fa8a48f7045517d7ae8991c"
    private const val FIXTURE_IMAGE =
      "docker.io/library/eclipse-temurin@sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e"
    private const val FIXTURE_JAVA_SHA =
      "1cedc51a4102638f1f06077acb3611b88f3061f9c7d76bd0a0df7f8607a9367b"

    private val PROTOCOL_SCHEMA_FILES =
      listOf(
        "calibration-provisional-v1.schema.json",
        "campaign-v1.schema.json",
        "capture-provisional-v1.schema.json",
        "capture-profile-family-v1.schema.json",
        "capture-v1.schema.json",
        "comparison-v1.schema.json",
        "distribution-classpath-v1.schema.json",
        "distribution-protocol-v1.schema.json",
        "distribution-provenance-v1.schema.json",
        "expected-cells-v1.schema.json",
        "postflight-v1.schema.json",
        "preflight-v1.schema.json",
        "profiler-summary-v1.schema.json",
        "regression-policy-v1.schema.json",
        "restoration-v1.schema.json",
        "watcher-v1.schema.json",
      )

    fun create(): DistributionFixture {
      val parent = Files.createTempDirectory("distribution-fixture-").toRealPath()
      val root = parent.resolve("distribution")
      Files.createDirectories(root)

      val javaExecutable =
        Path.of(
            checkNotNull(ProcessHandle.current().info().command().orElse(null)) {
              "current Java executable is unavailable"
            },
          )
          .toAbsolutePath()
          .normalize()
      val javaIdentity =
        JavaRuntimeIdentity(
          executable = javaExecutable,
          featureVersion = Runtime.version().feature(),
          sha256 = Sha256.digest(Files.readAllBytes(javaExecutable)),
        )
      return DistributionFixture(root, javaIdentity).apply { createValidDistribution() }
    }

    fun compiledClass(
      binaryName: String,
      members: String = "",
      publicType: Boolean = true,
      release: Int = Runtime.version().feature(),
    ): ByteArray {
      val packageName = binaryName.substringBeforeLast('.', missingDelimiterValue = "")
      val simpleName = binaryName.substringAfterLast('.')
      val source =
        buildString {
          if (packageName.isNotEmpty()) {
            append("package ").append(packageName).append(";\n")
          }
          if (publicType) {
            append("public ")
          }
          append("class ").append(simpleName).append(" {\n")
          append(members).append('\n')
          append("}\n")
        }
      return compile(binaryName, source, release)
    }

    fun compiledModuleInfo(moduleName: String): ByteArray =
      compile("module-info", "module $moduleName {}\n", 9)

    fun writeJar(
      path: Path,
      entries: Map<String, ByteArray>,
      multiRelease: Boolean = false,
    ) {
      Files.createDirectories(path.parent)
      val manifest =
        Manifest().apply {
          mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
          if (multiRelease) {
            mainAttributes.putValue("Multi-Release", "true")
          }
        }
      JarOutputStream(Files.newOutputStream(path)).use { output ->
        output.putNextEntry(JarEntry("META-INF/MANIFEST.MF").apply { time = 0L })
        output.write(ByteArrayOutputStream().also(manifest::write).toByteArray())
        output.closeEntry()
        entries.toSortedMap().forEach { (name, bytes) ->
          output.putNextEntry(JarEntry(name).apply { time = 0L })
          output.write(bytes)
          output.closeEntry()
        }
      }
    }

    private fun portablePath(path: Path): String =
      path.joinToString(separator = "/") { it.toString() }

    private fun compile(binaryName: String, source: String, release: Int): ByteArray {
      val key = CompilationKey(binaryName, source, release)
      return synchronized(COMPILED_CLASSES) {
        COMPILED_CLASSES.getOrPut(key) {
          val directory = Files.createTempDirectory("distribution-compiled-class-")
          try {
            val sourcePath =
              directory.resolve("source").resolve(binaryName.replace('.', '/') + ".java")
            val output = directory.resolve("classes")
            Files.createDirectories(sourcePath.parent)
            Files.createDirectories(output)
            Files.writeString(sourcePath, source)
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
            val exitCode =
              compiler.run(
                null,
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream(),
                "--release",
                release.toString(),
                "-g:none",
                "-d",
                output.toString(),
                sourcePath.toString(),
              )
            check(exitCode == 0) { "fixture Java compilation failed" }
            Files.readAllBytes(output.resolve(binaryName.replace('.', '/') + ".class"))
          } finally {
            directory.toFile().deleteRecursively()
          }
        }
      }.copyOf()
    }

    private data class CompilationKey(
      val binaryName: String,
      val source: String,
      val release: Int,
    )

    private val COMPILED_CLASSES = mutableMapOf<CompilationKey, ByteArray>()
  }

  private fun createValidDistribution() {
    writeJar(
      root.resolve(PRODUCTION_JAR),
      mapOf("example/Application.class" to compiledClass("example.Application")),
    )
    writeJar(
      root.resolve(BENCHMARK_JAR),
      mapOf(
        "META-INF/BenchmarkList" to benchmarkList(EXPECTED_BENCHMARK).encodeToByteArray(),
        "META-INF/CompilerHints" to "dontinline,example.Benchmark.measure\n".encodeToByteArray(),
        "example/Benchmark.class" to
          compiledClass("example.Benchmark", "public void measure() {}"),
      ),
    )
    writeJar(
      root.resolve(BENCHMARK_DEPENDENCY),
      mapOf(
        "META-INF/services/example.Service" to "example.Provider\n".encodeToByteArray(),
        "example/Dependency.class" to compiledClass("example.Dependency"),
        "example/Provider.class" to compiledClass("example.Provider"),
        "example/Service.class" to compiledClass("example.Service"),
      ),
    )
    writeJar(
      root.resolve(RUNNER_JAR),
      mapOf("performance/Runner.class" to compiledClass("performance.Runner")),
    )
    writeJar(
      root.resolve(RUNNER_DEPENDENCY),
      mapOf(
        "performance/RunnerDependency.class" to compiledClass("performance.RunnerDependency"),
      ),
    )

    write(UNIX_LAUNCHER, "#!/bin/sh\nexit 0\n".encodeToByteArray())
    write(WINDOWS_LAUNCHER, "@echo off\r\nexit /b 0\r\n".encodeToByteArray())
    write("protocol/adapter/run", "#!/bin/sh\nexit 0\n".encodeToByteArray())
    listOf("canary", "cold", "warm").forEach { profile ->
      write("protocol/profiles/$profile.json", "{}\n".encodeToByteArray())
    }
    listOf("linux-arm64", "m4max-docker", "github-hosted").forEach { runtime ->
      write("protocol/runtime/$runtime.json", "{}\n".encodeToByteArray())
    }
    listOf("m4max-docker", "github-hosted").forEach { policy ->
      write("protocol/qualification/$policy.json", "{}\n".encodeToByteArray())
    }
    write("protocol/expected-cells.json", "{}\n".encodeToByteArray())
    write("protocol/test-vectors/bootstrap-v1.json", "{}\n".encodeToByteArray())
    PROTOCOL_SCHEMA_FILES
      .forEach { schema ->
        val resource = "/performance/protocol/schemas/$schema"
        val bytes =
          checkNotNull(DistributionFixture::class.java.getResourceAsStream(resource)) {
              "missing test schema $resource"
            }
            .use { it.readAllBytes() }
        write("protocol/schemas/$schema", bytes)
      }

    write(CLASSPATH_MANIFEST, CanonicalJson.encode(classpathDocument()))
    write(PROVENANCE_MANIFEST, CanonicalJson.encode(provenanceDocument()))
    write(PROTOCOL_MANIFEST, CanonicalJson.encode(protocolDocument()))
    reseal()
  }

  private fun comparisonExpectedCells(multiCell: Boolean): ByteArray =
    CanonicalJson.encode(
      JsonNodeFactory.instance.objectNode().apply {
        put(
          "\$schema",
          "https://revoman.dev/performance/protocol/schemas/expected-cells-v1.schema.json",
        )
        put("schemaVersion", "expected-cells-v1")
        set(
          "families",
          JsonNodeFactory.instance.objectNode().apply {
            set("canary", JsonNodeFactory.instance.arrayNode())
            set("cold", JsonNodeFactory.instance.arrayNode())
            set(
              "warm",
              JsonNodeFactory.instance.arrayNode().apply {
                add(
                  JsonNodeFactory.instance.objectNode().apply {
                    put("benchmark", COMPARISON_BENCHMARK)
                    set("parameters", JsonNodeFactory.instance.objectNode())
                  },
                )
                if (multiCell) {
                  add(
                    JsonNodeFactory.instance.objectNode().apply {
                      put("benchmark", COMPARISON_BENCHMARK)
                      set(
                        "parameters",
                        JsonNodeFactory.instance.objectNode().put("variant", "secondary"),
                      )
                    },
                  )
                }
              },
            )
          },
        )
      },
    )

  private fun comparisonProfile(family: String): ByteArray =
    CanonicalJson.encode(
      JsonNodeFactory.instance.objectNode().apply {
        put("family", family)
        set(
          "jvmArguments",
          JsonNodeFactory.instance.arrayNode().apply {
            add("-Xms2g")
            add("-Xmx2g")
          },
        )
        set(
          "variants",
          JsonNodeFactory.instance.arrayNode().apply {
            if (family == "warm") {
              listOf(10, 20, 40).forEach { forks ->
                add(
                  JsonNodeFactory.instance.objectNode().apply {
                    put("identity", "warm-$forks-none-v1")
                    put("forks", forks)
                    put("warmupIterations", 5)
                    put("measurementIterations", 10)
                    put("profiler", "none")
                  },
                )
              }
            }
          },
        )
      },
    )

  private fun comparisonRuntimeProfile(): ByteArray =
    CanonicalJson.encode(
      JsonNodeFactory.instance.objectNode().apply {
        put("profileKind", "runtime")
        put("profileId", "fixture-java-21-linux-arm64-v1")
        set(
          "image",
          JsonNodeFactory.instance.objectNode().apply {
            put("reference", FIXTURE_IMAGE)
            put("manifestDigest", FIXTURE_MANIFEST_DIGEST)
            put("ociConfigDigest", FIXTURE_CONFIG_DIGEST)
            put("architecture", "arm64")
          },
        )
        set(
          "java",
          JsonNodeFactory.instance.objectNode().apply {
            put("release", "21.0.11+10-LTS")
            put("vendor", "Eclipse Adoptium")
            put("sha256", FIXTURE_JAVA_SHA)
          },
        )
      },
    )

  private fun comparisonSubstrateProfile(profileId: String, uid: Int): ByteArray =
    CanonicalJson.encode(
      JsonNodeFactory.instance.objectNode().apply {
        put("profileKind", "substrate")
        put("profileId", profileId)
        put("runtimeProfileId", "fixture-java-21-linux-arm64-v1")
        put("runtimeReference", FIXTURE_IMAGE)
        set(
          "identity",
          JsonNodeFactory.instance.objectNode().apply {
            put("uid", uid)
            put("gid", uid)
          },
        )
        set(
          "limits",
          JsonNodeFactory.instance.objectNode().apply {
            put("cpusetCpus", "0-3")
            put("memoryBytes", 6442450944L)
            put("memorySwapBytes", 6442450944L)
            put("pidsLimit", 512)
          },
        )
        set(
          "security",
          JsonNodeFactory.instance.objectNode().apply {
            put("readOnlyRoot", true)
            set(
              "capDrop",
              JsonNodeFactory.instance.arrayNode().add("ALL"),
            )
            set(
              "securityOpt",
              JsonNodeFactory.instance.arrayNode().add("no-new-privileges"),
            )
            set(
              "networklessPhases",
              JsonNodeFactory.instance.arrayNode().add("timed"),
            )
          },
        )
        set(
          "environment",
          JsonNodeFactory.instance.objectNode().apply {
            put("LANG", "C.UTF-8")
            put("LC_ALL", "C.UTF-8")
            put("TZ", "UTC")
          },
        )
      },
    )

  private fun classpathDocument(): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("schemaVersion", "distribution-classpath-v1")
      set(
        "javaRuntime",
        JsonNodeFactory.instance.objectNode().apply {
          put("executable", selectedJava.executable.toString())
          put("executableSha256", selectedJava.sha256.hex)
          put("featureVersion", selectedJava.featureVersion)
        },
      )
      set(
        "benchmarkClasspath",
        JsonNodeFactory.instance.arrayNode().apply {
          add(classpathEntry(0, BENCHMARK_JAR, "com.salesforce.revoman:benchmarks:1"))
          add(classpathEntry(1, PRODUCTION_JAR, "com.salesforce.revoman:revoman:1"))
          add(classpathEntry(2, BENCHMARK_DEPENDENCY, "example:benchmark-dependency:1"))
        },
      )
      set(
        "runnerClasspath",
        JsonNodeFactory.instance.arrayNode().apply {
          add(classpathEntry(0, RUNNER_JAR, "com.salesforce.revoman:performance-runner:1"))
          add(classpathEntry(1, RUNNER_DEPENDENCY, "example:runner-dependency:1"))
        },
      )
      set(
        "embeddedDependencies",
        JsonNodeFactory.instance.arrayNode().apply {
          add(
            JsonNodeFactory.instance.objectNode().apply {
              put("coordinate", "org.jetbrains.kotlinx:kotlinx-collections-immutable:1")
              put("placement", "embedded:app/revoman.jar")
              put("sha256", SHA)
            },
          )
        },
      )
      set(
        "expectedBenchmarks",
        JsonNodeFactory.instance.arrayNode().apply { add(EXPECTED_BENCHMARK) },
      )
    }

  private fun provenanceDocument(): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("schemaVersion", "distribution-provenance-v1")
      set("treatment", gitIdentity("1".repeat(40)))
      set("immutableHarness", gitIdentity("2".repeat(40)))
      set("distributionFreezer", gitIdentity("3".repeat(40)))
    }

  private fun protocolDocument(): ObjectNode {
    val launchers = listOf(UNIX_LAUNCHER, WINDOWS_LAUNCHER)
    val schemas = PROTOCOL_SCHEMA_FILES.map { "protocol/schemas/$it" }
    val profiles = listOf("canary", "cold", "warm").map { "protocol/profiles/$it.json" }
    val runtimes =
      listOf("linux-arm64", "m4max-docker", "github-hosted").map {
        "protocol/runtime/$it.json"
      }
    val policies =
      listOf("m4max-docker", "github-hosted").map {
        "protocol/qualification/$it.json"
      }
    return JsonNodeFactory.instance.objectNode().apply {
      put("schemaVersion", "distribution-protocol-v1")
      put("protocolSha256", SHA)
      set("runner", artifact(RUNNER_JAR))
      set("adapter", artifact("protocol/adapter/run"))
      set("launchers", artifactArray(launchers))
      set("schemas", artifactArray(schemas))
      set("profiles", artifactArray(profiles))
      set("runtimeDeclarations", artifactArray(runtimes))
      set("qualificationPolicies", artifactArray(policies))
      set("expectedCells", artifact("protocol/expected-cells.json"))
      set(
        "testVectors",
        artifactArray(listOf("protocol/test-vectors/bootstrap-v1.json")),
      )
      set(
        "sourceClosure",
        JsonNodeFactory.instance.arrayNode().apply {
          listOf(
              "build.gradle.kts" to SHA,
              "buildSrc/performance-runner/src/main/kotlin/performance/compare/BootstrapV1.kt" to
                "1".repeat(64),
              "buildSrc/performance-runner/src/main/kotlin/performance/compare/CalibrationBundleVerifier.kt" to
                "2".repeat(64),
              "buildSrc/performance-runner/src/main/kotlin/performance/compare/CaptureBundleVerifier.kt" to
                "3".repeat(64),
              "buildSrc/performance-runner/src/main/kotlin/performance/compare/CaptureComparator.kt" to
                "4".repeat(64),
              "buildSrc/performance-runner/src/main/kotlin/performance/compare/CaptureCompatibility.kt" to
                "5".repeat(64),
              "buildSrc/performance-runner/src/main/kotlin/performance/compare/CellIdentity.kt" to
                "6".repeat(64),
              "buildSrc/performance-runner/src/main/kotlin/performance/compare/ComparisonRenderer.kt" to
                "b".repeat(64),
              "source/src/jmh/kotlin/com/salesforce/revoman/benchmark/RevUpV3WarmBenchmark.kt" to
                "d".repeat(64),
              "source/src/jmh/resources/performance/log4j2-performance.xml" to
                "a".repeat(64),
              "source/src/main/resources/revup-v3-workload-tree-source.json" to
                "14".repeat(32),
            )
            .sortedBy(Pair<String, String>::first)
            .forEach { (path, sha256) ->
              add(
                JsonNodeFactory.instance.objectNode().apply {
                  put("path", path)
                  put("sha256", sha256)
                },
              )
            }
        },
      )
      set(
        "toolIdentities",
        JsonNodeFactory.instance.objectNode().apply {
          put("gradle", "9.7.0")
          put("javaExecutableSha256", selectedJava.sha256.hex)
          put("javaFeature", selectedJava.featureVersion.toString())
          put("jmhCore", "1.37")
          put("jmhGradlePlugin", "0.7.3")
          put("kotlinCompiler", "2.4.20-RC")
          put("runtimeImage", "docker.io/library/eclipse-temurin@sha256:${"a".repeat(64)}")
        },
      )
      refreshProtocolHash(this)
    }
  }

  private fun artifact(relativePath: String): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("path", relativePath)
      put("sha256", digest(relativePath).hex)
    }

  private fun artifactArray(paths: List<String>): ArrayNode =
    JsonNodeFactory.instance.arrayNode().apply { paths.sorted().forEach { add(artifact(it)) } }

  private fun gitIdentity(sha: String): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("gitSha", sha)
      put("treeClean", true)
    }

  private fun protocolArtifactObjects(protocol: ObjectNode): Sequence<ObjectNode> =
    sequence {
      yield(protocol.get("runner") as ObjectNode)
      yield(protocol.get("adapter") as ObjectNode)
      yieldAll(protocol.arrayNode("launchers").values().asSequence().map { it as ObjectNode })
      yieldAll(protocol.arrayNode("schemas").values().asSequence().map { it as ObjectNode })
      yieldAll(protocol.arrayNode("profiles").values().asSequence().map { it as ObjectNode })
      yieldAll(
        protocol.arrayNode("runtimeDeclarations").values().asSequence().map { it as ObjectNode },
      )
      yieldAll(
        protocol.arrayNode("qualificationPolicies").values().asSequence().map {
          it as ObjectNode
        },
      )
      yield(protocol.get("expectedCells") as ObjectNode)
      yieldAll(protocol.arrayNode("testVectors").values().asSequence().map { it as ObjectNode })
    }

  private fun ObjectNode.arrayNode(name: String): ArrayNode = get(name) as ArrayNode

  internal fun benchmarkList(benchmark: String): String {
    val className = benchmark.substringBeforeLast('.')
    val generatedClassName = "${className}_jmhType"
    val method = benchmark.substringAfterLast('.')
    return "JMH S ${className.length} $className S ${generatedClassName.length} $generatedClassName S ${method.length} $method E E E E E E E E E E E E E E E E E E\n"
  }
}
