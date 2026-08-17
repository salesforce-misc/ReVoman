/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.support

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
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

  fun setChecksumManifest(lines: List<String>) {
    Files.writeString(
      root.resolve(CHECKSUM_MANIFEST),
      lines.joinToString(separator = "\n", postfix = "\n"),
    )
  }

  fun checksumLines(): List<String> = Files.readAllLines(root.resolve(CHECKSUM_MANIFEST))

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
      .forEach { it.put("sha256", digest) }
    write(CLASSPATH_MANIFEST, CanonicalJson.encode(classpath))

    val protocol = jsonObject(PROTOCOL_MANIFEST)
    protocolArtifactObjects(protocol)
      .filter { it.get("path").asString() == relativePath }
      .forEach { it.put("sha256", digest) }
    write(PROTOCOL_MANIFEST, CanonicalJson.encode(protocol))
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
    const val CLASSPATH_MANIFEST = "metadata/classpath.json"
    const val PROVENANCE_MANIFEST = "metadata/provenance.json"
    const val PROTOCOL_MANIFEST = "metadata/protocol.json"
    const val CHECKSUM_MANIFEST = "metadata/distribution.sha256"
    const val EXPECTED_BENCHMARK = "example.Benchmark.measure"

    private const val SHA =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private val PROTOCOL_SCHEMA_FILES =
      listOf(
        "adapter-failure-v1.schema.json",
        "capture-provisional-v1.schema.json",
        "capture-v1.schema.json",
        "distribution-classpath-v1.schema.json",
        "distribution-protocol-v1.schema.json",
        "distribution-provenance-v1.schema.json",
        "postflight-v1.schema.json",
        "preflight-v1.schema.json",
        "profiler-summary-v1.schema.json",
        "restoration-v1.schema.json",
        "watcher-v1.schema.json",
      )

    fun create(): DistributionFixture {
      val parent = Files.createTempDirectory("distribution-fixture-")
      val root = parent.resolve("distribution")
      Files.createDirectories(root)

      val javaExecutable = parent.resolve("java/bin/java")
      Files.createDirectories(javaExecutable.parent)
      Files.writeString(javaExecutable, "fixture-java-21")
      val javaIdentity =
        JavaRuntimeIdentity(
          executable = javaExecutable,
          featureVersion = 21,
          sha256 = Sha256.digest(Files.readAllBytes(javaExecutable)),
        )
      return DistributionFixture(root, javaIdentity).apply { createValidDistribution() }
    }

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
      JarOutputStream(Files.newOutputStream(path), manifest).use { output ->
        entries.toSortedMap().forEach { (name, bytes) ->
          output.putNextEntry(JarEntry(name))
          output.write(bytes)
          output.closeEntry()
        }
      }
    }

    private fun portablePath(path: Path): String =
      path.joinToString(separator = "/") { it.toString() }
  }

  private fun createValidDistribution() {
    writeJar(
      root.resolve(PRODUCTION_JAR),
      mapOf("example/Application.class" to byteArrayOf(1)),
    )
    writeJar(
      root.resolve(BENCHMARK_JAR),
      mapOf(
        "META-INF/BenchmarkList" to benchmarkList(EXPECTED_BENCHMARK).encodeToByteArray(),
        "META-INF/CompilerHints" to "dontinline,example.Benchmark.measure\n".encodeToByteArray(),
        "example/Benchmark.class" to byteArrayOf(2),
      ),
    )
    writeJar(
      root.resolve(BENCHMARK_DEPENDENCY),
      mapOf(
        "META-INF/services/example.Service" to "example.Provider\n".encodeToByteArray(),
        "example/Dependency.class" to byteArrayOf(3),
        "example/Provider.class" to byteArrayOf(4),
        "example/Service.class" to byteArrayOf(5),
      ),
    )
    writeJar(
      root.resolve(RUNNER_JAR),
      mapOf("performance/Runner.class" to byteArrayOf(6)),
    )
    writeJar(
      root.resolve(RUNNER_DEPENDENCY),
      mapOf("performance/RunnerDependency.class" to byteArrayOf(7)),
    )

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
          add(classpathEntry(0, BENCHMARK_JAR, "com.salesforce.revoman:benchmarks"))
          add(classpathEntry(1, PRODUCTION_JAR, "com.salesforce.revoman:revoman"))
          add(classpathEntry(2, BENCHMARK_DEPENDENCY, "example:benchmark-dependency"))
        },
      )
      set(
        "runnerClasspath",
        JsonNodeFactory.instance.arrayNode().apply {
          add(classpathEntry(0, RUNNER_JAR, "com.salesforce.revoman:performance-runner"))
          add(classpathEntry(1, RUNNER_DEPENDENCY, "example:runner-dependency"))
        },
      )
      set(
        "embeddedDependencies",
        JsonNodeFactory.instance.arrayNode().apply {
          add(
            JsonNodeFactory.instance.objectNode().apply {
              put("coordinate", "org.jetbrains.kotlinx:kotlinx-collections-immutable")
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
    val protocolArtifacts =
      listOf(
        RUNNER_JAR,
        "protocol/adapter/run",
        *schemas.toTypedArray(),
        *profiles.toTypedArray(),
        *runtimes.toTypedArray(),
        *policies.toTypedArray(),
        "protocol/expected-cells.json",
        "protocol/test-vectors/bootstrap-v1.json",
      )
    val protocolHash =
      Sha256.digest(
        protocolArtifacts
          .sorted()
          .joinToString(separator = "\n", postfix = "\n") { path ->
            "${digest(path).hex}  $path"
          }
          .encodeToByteArray(),
      )

    return JsonNodeFactory.instance.objectNode().apply {
      put("schemaVersion", "distribution-protocol-v1")
      put("protocolSha256", protocolHash.hex)
      set("runner", artifact(RUNNER_JAR))
      set("adapter", artifact("protocol/adapter/run"))
      set("schemas", artifactArray(schemas))
      set("profiles", artifactArray(profiles))
      set("runtimeDeclarations", artifactArray(runtimes))
      set("qualificationPolicies", artifactArray(policies))
      set("expectedCells", artifact("protocol/expected-cells.json"))
      set(
        "testVectors",
        artifactArray(listOf("protocol/test-vectors/bootstrap-v1.json")),
      )
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
