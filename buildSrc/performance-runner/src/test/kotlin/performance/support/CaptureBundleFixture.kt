/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.support

import java.math.BigDecimal
import java.math.MathContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import performance.hash.Sha256
import performance.json.CanonicalJson
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** A real schema-valid, checksum-sealed capture directory for comparator boundary tests. */
internal class CaptureBundleFixture private constructor(
  val root: Path,
) {
  fun captureDocument(): ObjectNode = jsonObject("capture.json")

  fun jmhResult(): ArrayNode =
    CanonicalJson.parseStrict(Files.readAllBytes(root.resolve("jmh-result.json"))).asArray()

  fun mutateCapture(mutation: (ObjectNode) -> Unit) {
    val document = captureDocument()
    mutation(document)
    write("capture.json", CanonicalJson.encode(document))
    reseal()
  }

  fun mutateJmh(mutation: (ArrayNode) -> Unit) {
    val rows = jmhResult()
    mutation(rows)
    write("jmh-result.json", CanonicalJson.encode(rows))
    reseal()
  }

  fun replaceForkSamples(forks: List<List<Double>>) {
    val rows = jmhResult()
    val row = rows.get(0).asObject()
    val rawData = JsonNodeFactory.instance.arrayNode()
    forks.forEach { observations ->
      rawData.add(
        JsonNodeFactory.instance.arrayNode().apply { observations.forEach { value -> add(value) } },
      )
    }
    row.get("primaryMetric").asObject().set("rawData", rawData)
    val document = captureDocument()
    val cell = document.get("cells").asArray().get(0).asObject()
    cell.get("sampleDimensions").asObject().apply {
      put("forks", forks.size)
      put("measurementIterations", forks.first().size)
      put("samplesPerFork", forks.first().size)
    }
    document.get("profile").asObject().apply {
      put("forks", forks.size)
      put("measurementIterations", forks.first().size)
    }
    row.put("forks", forks.size)
    row.put("measurementIterations", forks.first().size)
    cell.set(
      "derivedForkSummaries",
      JsonNodeFactory.instance.arrayNode().apply {
        forks.forEachIndexed { index, values ->
          val decimals = values.map(BigDecimal::valueOf)
          val mean =
            decimals.reduce(BigDecimal::add).divide(BigDecimal(decimals.size), MathContext.DECIMAL128)
          add(
            JsonNodeFactory.instance.objectNode().apply {
              put("fork", index + 1)
              put("sampleCount", values.size)
              put("score", mean)
            },
          )
        }
      },
    )
    cell
      .get("jmhResultRow")
      .asObject()
      .put("sha256", Sha256.digest(CanonicalJson.encode(row)).hex)
    write("jmh-result.json", CanonicalJson.encode(rows))
    write("capture.json", CanonicalJson.encode(document))
    reseal()
  }

  fun writeRaw(relative: String, bytes: ByteArray) {
    write(relative, bytes)
  }

  fun addAndReseal(relative: String, bytes: ByteArray) {
    write(relative, bytes)
    reseal()
  }

  fun deleteAndReseal(relative: String) {
    Files.delete(root.resolve(relative))
    reseal()
  }

  fun reseal() {
    val names =
      Files.list(root).use { paths ->
        paths
          .filter(Files::isRegularFile)
          .map { it.fileName.toString() }
          .filter { it != "checksums.sha256" }
          .sorted()
          .toList()
      }
    val manifest = names.joinToString(separator = "\n", postfix = "\n") { relative ->
      "${Sha256.digest(root.resolve(relative)).hex}  $relative"
    }
    write("checksums.sha256", manifest.encodeToByteArray())
  }

  fun close() {
    root.parent.toFile().deleteRecursively()
  }

  private fun jsonObject(relative: String): ObjectNode =
    CanonicalJson.parseStrict(Files.readAllBytes(root.resolve(relative))).asObject()

  private fun write(relative: String, bytes: ByteArray) {
    Files.write(root.resolve(relative), bytes)
  }

  companion object {
    fun create(
      distribution: DistributionFixture,
      captureId: String = "a1",
      processRunId: String = "process-a1",
      sessionId: String = "session",
      sequence: Int = 1,
      treatmentSha: String? = null,
      productionSha: Sha256? = null,
      distributionSha: Sha256? = null,
      freezerSha: String? = null,
      captureRunnerSha: String = "4".repeat(40),
      profiler: String = "none",
      startedAtUtc: String = "2026-08-17T00:00:00Z",
      completedAtUtc: String = "2026-08-17T00:01:00Z",
      forkSamples: List<List<Double>> = List(10) { List(10) { 10.0 } },
    ): CaptureBundleFixture {
      val root = Files.createTempDirectory("capture-bundle-fixture-").toRealPath().resolve("capture")
      Files.createDirectories(root)
      val classpath = distribution.jsonObject(DistributionFixture.CLASSPATH_MANIFEST)
      val protocol = distribution.jsonObject(DistributionFixture.PROTOCOL_MANIFEST)
      val provenance = distribution.jsonObject(DistributionFixture.PROVENANCE_MANIFEST)
      val warmProfile = distribution.jsonObject("protocol/profiles/warm.json")
      val variant = warmProfile.get("variants").asArray().get(0).asObject()
      val policySha = protocol.binding("qualificationPolicies", "protocol/qualification/m4max-docker.json")
      val comparatorSha = protocol.comparatorImplementationAggregate()
      val rendererSha = protocol.source("ComparisonRenderer.kt")
      val benchmarkSourceSha = protocol.benchmarkSourceAggregate()
      val workloadTreeSha = distribution.workloadTreeSha256()
      val captureSchemaSha = protocol.binding("schemas", "protocol/schemas/capture-v1.schema.json")
      val adapterSha = protocol.get("adapter").asObject().sha()
      val benchmarkEntries = classpath.get("benchmarkClasspath").asArray()
      val runnerEntries = classpath.get("runnerClasspath").asArray()
      val productionEntry = benchmarkEntries.entry(DistributionFixture.PRODUCTION_JAR)
      val benchmarkEntry = benchmarkEntries.entry(DistributionFixture.BENCHMARK_JAR)
      val selectedProduction = productionSha ?: productionEntry.sha()
      val selectedDistribution =
        distributionSha ?: Sha256.digest(distribution.root.resolve(DistributionFixture.CHECKSUM_MANIFEST))
      val cellSpecs =
        distribution
          .jsonObject("protocol/expected-cells.json")
          .get("families")
          .asObject()
          .get("warm")
          .asArray()
          .values()
          .asSequence()
          .map { cell ->
            val objectCell = cell.asObject()
            objectCell.get("benchmark").asString() to objectCell.get("parameters").asObject()
          }
          .toList()
      val rows =
        cellSpecs.map { (benchmark, parameters) ->
          jmhRow(benchmark, parameters, forkSamples)
        }
      val document = validCaptureDocument()
      document.get("identity").asObject().apply {
        put("captureId", captureId)
        put("processRunId", processRunId)
        put("performanceSessionId", sessionId)
        put("sessionSequence", sequence)
      }
      document.get("outcome").asObject().apply {
        put("startedAtUtc", startedAtUtc)
        put("completedAtUtc", completedAtUtc)
      }
      document.get("provenance").asObject().apply {
        set("treatment", provenance.get("treatment").deepCopy())
        set("immutableHarness", provenance.get("immutableHarness").deepCopy())
        set("distributionFreezer", provenance.get("distributionFreezer").deepCopy())
        get("treatment").asObject().apply {
          treatmentSha?.let { put("gitSha", it) }
        }
        get("distributionFreezer").asObject().apply {
          freezerSha?.let { put("gitSha", it) }
        }
        set(
          "captureRunner",
          JsonNodeFactory.instance.objectNode().apply {
            put("gitSha", captureRunnerSha)
            put("treeClean", true)
          },
        )
      }
      document.get("protocol").asObject().apply {
        put("benchmarkSourceSha256", benchmarkSourceSha.hex)
        put("benchmarkProtocolSha256", protocol.get("protocolSha256").asString())
        put("qualificationPolicySha256", policySha.hex)
        put("hostAdapterSha256", adapterSha.hex)
        put("schemaSha256", captureSchemaSha.hex)
        put("rendererSha256", rendererSha.hex)
        put("comparatorSha256", comparatorSha.hex)
        put("workloadTreeSha256", workloadTreeSha.hex)
      }
      document.get("artifacts").asObject().apply {
        set("production", artifact(DistributionFixture.PRODUCTION_JAR, selectedProduction))
        set("benchmark", artifact(DistributionFixture.BENCHMARK_JAR, benchmarkEntry.sha()))
        set(
          "distribution",
          artifact(DistributionFixture.CHECKSUM_MANIFEST, selectedDistribution),
        )
        set(
          "orderedClasspath",
          JsonNodeFactory.instance.arrayNode().apply {
            benchmarkEntries.forEach { value ->
              val entry = value.asObject()
              add(
                artifact(
                  entry.get("path").asString(),
                  if (entry.get("path").asString() == DistributionFixture.PRODUCTION_JAR) {
                    selectedProduction
                  } else {
                    entry.sha()
                  },
                ),
              )
            }
          },
        )
        set("executingRunner", runnerEntries.get(0).asObject().artifact())
        set(
          "orderedRunnerClasspath",
          JsonNodeFactory.instance.arrayNode().apply {
            runnerEntries.forEach { value -> add(value.asObject().artifact()) }
          },
        )
        set(
          "dependencies",
          JsonNodeFactory.instance.arrayNode().apply {
            benchmarkEntries
              .filter { value ->
                value.asObject().get("path").asString() !in
                  setOf(DistributionFixture.PRODUCTION_JAR, DistributionFixture.BENCHMARK_JAR)
              }
              .forEach { value ->
                val entry = value.asObject()
                add(dependency(entry.get("coordinate").asString(), entry.sha()))
              }
            classpath.get("embeddedDependencies").asArray().forEach { value ->
              val entry = value.asObject()
              add(dependency(entry.get("coordinate").asString(), entry.sha()))
            }
          },
        )
      }
      document.get("toolchain").asObject().apply {
        val tools = protocol.get("toolIdentities").asObject()
        put("gradleVersion", tools.get("gradle").asString())
        put("jmhPluginVersion", tools.get("jmhGradlePlugin").asString())
        put("jmhCoreVersion", tools.get("jmhCore").asString())
        put("kotlinCompilerVersion", tools.get("kotlinCompiler").asString())
      }
      document.get("runtime").asObject().get("jdk").asObject().apply {
        put(
          "binarySha256",
          "1cedc51a4102638f1f06077acb3611b88f3061f9c7d76bd0a0df7f8607a9367b",
        )
        put("vendor", "Eclipse Adoptium")
        put("version", "21.0.11+10-LTS")
      }
      document.get("runtime").asObject().get("oci").asObject().apply {
        put(
          "imageReference",
          "docker.io/library/eclipse-temurin@sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e",
        )
        put(
          "platformManifestDigest",
          "sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e",
        )
        put(
          "configDigest",
          "sha256:ad6963934ee96838c09d99f3c4df6f991cd00ed70fa8a48f7045517d7ae8991c",
        )
      }
      document.get("qualification").asObject().put("policyHash", policySha.hex)
      document.get("profile").asObject().apply {
        put("family", "warm")
        put("identity", variant.get("identity").asString())
        put("variantSha256", Sha256.digest(CanonicalJson.encode(variant)).hex)
        put("forks", variant.get("forks").asInt())
        put("warmupIterations", variant.get("warmupIterations").asInt())
        put("measurementIterations", variant.get("measurementIterations").asInt())
        put("profiler", profiler)
      }
      document.set(
        "cells",
        JsonNodeFactory.instance.arrayNode().apply {
          rows.forEachIndexed { index, row ->
            val (benchmark, parameters) = cellSpecs[index]
            add(captureCell(row, forkSamples, index, benchmark, parameters))
          }
        },
      )
      write(root, "capture.json", CanonicalJson.encode(document))
      write(
        root,
        "jmh-result.json",
        CanonicalJson.encode(
          JsonNodeFactory.instance.arrayNode().apply { rows.forEach(::add) },
        ),
      )
      write(root, "stdout.log", ByteArray(0))
      write(root, "stderr.log", ByteArray(0))
      return CaptureBundleFixture(root).apply { reseal() }
    }

    private fun jmhRow(
      benchmark: String,
      parameters: ObjectNode,
      forkSamples: List<List<Double>>,
    ): ObjectNode =
      JsonNodeFactory.instance.objectNode().apply {
        put("benchmark", benchmark)
        put("forks", forkSamples.size)
        put("measurementBatchSize", 1)
        put("measurementIterations", forkSamples.first().size)
        put("mode", "ss")
        set("params", parameters.deepCopy())
        set(
          "primaryMetric",
          JsonNodeFactory.instance.objectNode().apply {
            set(
              "rawData",
              JsonNodeFactory.instance.arrayNode().apply {
                forkSamples.forEach { observations ->
                  add(
                    JsonNodeFactory.instance.arrayNode().apply {
                      observations.forEach { value -> add(value) }
                    },
                  )
                }
              },
            )
            put("scoreUnit", "ms/op")
          },
        )
        set("secondaryMetrics", JsonNodeFactory.instance.objectNode())
        put("threads", 1)
        put("warmupBatchSize", 1)
        put("warmupIterations", 5)
      }

    private fun captureCell(
      row: ObjectNode,
      forkSamples: List<List<Double>>,
      rowIndex: Int,
      benchmark: String,
      parameters: ObjectNode,
    ): ObjectNode =
      JsonNodeFactory.instance.objectNode().apply {
        put("benchmark", benchmark)
        set("parameters", parameters.deepCopy())
        put("mode", "ss")
        put("unit", "ms/op")
        put("threads", 1)
        put("batchSize", 1)
        set(
          "primaryMetric",
          JsonNodeFactory.instance.objectNode().apply {
            put("name", "score")
            put("direction", "lowerIsBetter")
          },
        )
        set(
          "jmhResultRow",
          JsonNodeFactory.instance.objectNode().apply {
            put("jsonPointer", "/$rowIndex")
            put("sha256", Sha256.digest(CanonicalJson.encode(row)).hex)
          },
        )
        set(
          "sampleDimensions",
          JsonNodeFactory.instance.objectNode().apply {
            put("forks", forkSamples.size)
            put("measurementIterations", forkSamples.first().size)
            put("samplesPerFork", forkSamples.first().size)
          },
        )
        set(
          "derivedForkSummaries",
          JsonNodeFactory.instance.arrayNode().apply {
            forkSamples.forEachIndexed { index, values ->
              val decimals = values.map(BigDecimal::valueOf)
              val mean =
                decimals
                  .reduce(BigDecimal::add)
                  .divide(BigDecimal(decimals.size), MathContext.DECIMAL128)
              add(
                JsonNodeFactory.instance.objectNode().apply {
                  put("fork", index + 1)
                  put("sampleCount", values.size)
                  put("score", mean)
                },
              )
            }
          },
        )
      }

    private fun validCaptureDocument(): ObjectNode =
      checkNotNull(
          CaptureBundleFixture::class.java.getResourceAsStream(
            "/performance/golden/capture/valid-capture.json",
          ),
        ) {
          "missing valid capture fixture"
        }
        .use { stream -> CanonicalJson.parseStrict(stream.readAllBytes()).asObject() }

    private fun artifact(path: String, sha256: Sha256): ObjectNode =
      JsonNodeFactory.instance.objectNode().apply {
        put("path", path)
        put("sha256", sha256.hex)
      }

    private fun dependency(coordinate: String, sha256: Sha256): ObjectNode =
      JsonNodeFactory.instance.objectNode().apply {
        put("coordinate", coordinate)
        put("sha256", sha256.hex)
      }

    private fun ObjectNode.artifact(): ObjectNode = artifact(get("path").asString(), sha())

    private fun ObjectNode.sha(): Sha256 = Sha256.parse(get("sha256").asString())

    private fun ArrayNode.entry(path: String): ObjectNode =
      values().asSequence().map { it.asObject() }.single { it.get("path").asString() == path }

    private fun ObjectNode.binding(array: String, path: String): Sha256 =
      get(array).asArray().entry(path).sha()

    private fun ObjectNode.source(fileName: String): Sha256 =
      get("sourceClosure")
        .asArray()
        .values()
        .asSequence()
        .map { it.asObject() }
        .single { it.get("path").asString().endsWith("/$fileName") }
        .sha()

    private fun ObjectNode.benchmarkSourceAggregate(): Sha256 {
      val entries =
        get("sourceClosure")
          .asArray()
          .values()
          .asSequence()
          .map { it.asObject() }
          .filter { it.get("path").asString().startsWith("source/src/jmh/") }
          .sortedBy { it.get("path").asString() }
          .toList()
      require(entries.isNotEmpty())
      return Sha256.digest(
        entries.joinToString(separator = "\n", postfix = "\n") { entry ->
          "source\t${entry.get("sha256").asString()}\t${entry.get("path").asString()}"
        }.encodeToByteArray()
      )
    }

    private fun ObjectNode.comparatorImplementationAggregate(): Sha256 {
      val names =
        setOf(
          "BootstrapV1.kt",
          "CalibrationBundleVerifier.kt",
          "CaptureBundleVerifier.kt",
          "CaptureComparator.kt",
          "CaptureCompatibility.kt",
          "CellIdentity.kt",
        )
      val entries =
        get("sourceClosure")
          .asArray()
          .values()
          .asSequence()
          .map { it.asObject() }
          .filter { entry -> names.any { name -> entry.get("path").asString().endsWith("/$name") } }
          .sortedBy { it.get("path").asString() }
          .toList()
      require(entries.size == names.size)
      return Sha256.digest(
        entries.joinToString(separator = "\n", postfix = "\n") { entry ->
          "source\t${entry.get("sha256").asString()}\t${entry.get("path").asString()}"
        }.encodeToByteArray()
      )
    }

    private fun DistributionFixture.workloadTreeSha256(): Sha256 =
      JarFile(root.resolve(DistributionFixture.BENCHMARK_JAR).toFile()).use { jar ->
        val entry = checkNotNull(jar.getJarEntry("META-INF/revoman/performance/revup-v3-tree.json"))
        Sha256.digest(jar.getInputStream(entry).use { input -> input.readAllBytes() })
      }

    private fun write(root: Path, relative: String, bytes: ByteArray) {
      Files.write(root.resolve(relative), bytes)
    }
  }
}

private fun DistributionFixture.jsonObject(relative: String): ObjectNode =
  CanonicalJson.parseStrict(Files.readAllBytes(root.resolve(relative))).asObject()
