/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.distribution

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import java.io.PrintWriter
import java.io.Writer
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.spi.ToolProvider
import performance.hash.Sha256
import performance.support.DistributionFixture
import performance.support.DistributionFixture.Companion.BENCHMARK_DEPENDENCY
import performance.support.DistributionFixture.Companion.BENCHMARK_JAR
import performance.support.DistributionFixture.Companion.CHECKSUM_MANIFEST
import performance.support.DistributionFixture.Companion.CLASSPATH_MANIFEST
import performance.support.DistributionFixture.Companion.EXPECTED_BENCHMARK
import performance.support.DistributionFixture.Companion.PRODUCTION_JAR
import performance.support.DistributionFixture.Companion.PROTOCOL_MANIFEST
import performance.support.DistributionFixture.Companion.PROVENANCE_MANIFEST
import performance.support.DistributionFixture.Companion.RUNNER_JAR
import performance.support.DistributionFixture.Companion.UNIX_LAUNCHER
import performance.support.DistributionFixture.Companion.compiledClass
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

class DistributionValidatorTest :
  FunSpec(
    {
      test("valid distribution preserves immutable declared classpath order before process request") {
        withFixture { fixture ->
          val processSpy = ProcessSpy()

          val validation = fixture.validateBeforeProcess(processSpy)
          val verified = validation.shouldBeInstanceOf<DistributionValidation.Valid>().distribution

          verified.root shouldBe fixture.root.toAbsolutePath().normalize()
          verified.benchmarkClasspath.map(fixture.root.toAbsolutePath().normalize()::relativize).map(
            ::portablePath,
          ) shouldContainExactly fixture.benchmarkClasspath
          verified.runnerClasspath.map(fixture.root.toAbsolutePath().normalize()::relativize).map(
            ::portablePath,
          ) shouldContainExactly fixture.runnerClasspath
          verified.metadata.classpath.schemaVersion shouldBe "distribution-classpath-v1"
          verified.metadata.provenance.schemaVersion shouldBe "distribution-provenance-v1"
          verified.metadata.protocol.schemaVersion shouldBe "distribution-protocol-v1"
          shouldThrow<UnsupportedOperationException> {
            (verified.benchmarkClasspath as MutableList<Path>).add(fixture.root.resolve("extra.jar"))
          }
          processSpy.requests shouldBe 1
        }
      }

      test("public validation proof exposes no constructor or implementation escape hatch") {
        VerifiedDistribution::class.java.isInterface shouldBe true
        VerifiedDistribution::class.java.constructors.toList().shouldBeEmpty()
        Modifier.isPublic(VerifiedDistribution::class.java.permittedSubclasses.single().modifiers) shouldBe
          false
      }

      listOf(CLASSPATH_MANIFEST, PROVENANCE_MANIFEST, PROTOCOL_MANIFEST).forEach { manifest ->
        test("$manifest rejects unknown properties through its strict versioned schema") {
          withFixture { fixture ->
            when (manifest) {
              CLASSPATH_MANIFEST -> fixture.mutateClasspath { it.put("unexpected", true) }
              PROVENANCE_MANIFEST -> fixture.mutateProvenance { it.put("unexpected", true) }
              PROTOCOL_MANIFEST -> fixture.mutateProtocol { it.put("unexpected", true) }
            }

            fixture.assertInvalid(DistributionProblem.METADATA_SCHEMA_INVALID)
          }
        }

        test("$manifest rejects an unknown schema version") {
          withFixture { fixture ->
            when (manifest) {
              CLASSPATH_MANIFEST ->
                fixture.mutateClasspath { it.put("schemaVersion", "distribution-classpath-v2") }
              PROVENANCE_MANIFEST ->
                fixture.mutateProvenance { it.put("schemaVersion", "distribution-provenance-v2") }
              PROTOCOL_MANIFEST ->
                fixture.mutateProtocol { it.put("schemaVersion", "distribution-protocol-v2") }
            }

            fixture.assertInvalid(DistributionProblem.METADATA_SCHEMA_INVALID)
          }
        }
      }

      test("classpath metadata records the one intentionally embedded dependency") {
        withFixture { fixture ->
          fixture.mutateClasspath { document ->
            document.get("embeddedDependencies").asArray().removeAll()
          }

          fixture.assertInvalid(DistributionProblem.METADATA_SCHEMA_INVALID)
        }
      }

      test("classpath metadata rejects a different embedded dependency") {
        withFixture { fixture ->
          fixture.mutateClasspath { document ->
            document
              .get("embeddedDependencies")
              .asArray()
              .get(0)
              .asObject()
              .put("coordinate", "example:unexpected")
          }

          fixture.assertInvalid(DistributionProblem.METADATA_SCHEMA_INVALID)
        }
      }

      test("noncanonical metadata is rejected before semantic parsing") {
        withFixture { fixture ->
          val canonical = Files.readString(fixture.root.resolve(PROVENANCE_MANIFEST))
          fixture.replaceFile(PROVENANCE_MANIFEST, " $canonical".encodeToByteArray())

          fixture.assertInvalid(DistributionProblem.METADATA_NOT_CANONICAL)
        }
      }

      test("missing checksum manifest is rejected") {
        withFixture { fixture ->
          fixture.delete(CHECKSUM_MANIFEST)

          fixture.assertInvalid(DistributionProblem.CHECKSUM_MANIFEST_MISSING)
        }
      }

      test("mutated bytes are rejected by checksum before artifact validation") {
        withFixture { fixture ->
          fixture.writeWithoutResealing(PRODUCTION_JAR, "mutated".encodeToByteArray())

          fixture.assertInvalid(DistributionProblem.CHECKSUM_MISMATCH)
        }
      }

      test("a distribution file missing from the checksum manifest is rejected") {
        withFixture { fixture ->
          fixture.setChecksumManifest(
            fixture.checksumLines().filterNot { it.endsWith("  $PRODUCTION_JAR") },
          )

          fixture.assertInvalid(DistributionProblem.CHECKSUM_ENTRY_MISSING)
        }
      }

      test("checksum entries for absent files are rejected") {
        withFixture { fixture ->
          val absentEntry = "${"0".repeat(64)}  lib/absent.jar"
          fixture.setChecksumManifest(
            (fixture.checksumLines() + absentEntry).sortedBy { it.substringAfter("  ") },
          )

          fixture.assertInvalid(DistributionProblem.CHECKSUM_ENTRY_TARGET_MISSING)
        }
      }

      test("checksum manifest must be strictly lexicographically ordered") {
        withFixture { fixture ->
          fixture.setChecksumManifest(fixture.checksumLines().reversed())

          fixture.assertInvalid(DistributionProblem.CHECKSUM_MANIFEST_INVALID)
        }
      }

      test("checksum digests must be lowercase SHA-256") {
        withFixture { fixture ->
          val lines = fixture.checksumLines().toMutableList()
          lines[0] = lines.first().uppercase()
          fixture.setChecksumManifest(lines)

          fixture.assertInvalid(DistributionProblem.CHECKSUM_MANIFEST_INVALID)
        }
      }

      test("files outside the frozen layout are rejected even when checksummed") {
        withFixture { fixture ->
          fixture.addFileAndReseal("private/unexpected.txt", "unexpected".encodeToByteArray())

          fixture.assertInvalid(DistributionProblem.INVALID_LAYOUT)
        }
      }

      test("empty directories outside the frozen layout are rejected") {
        withFixture { fixture ->
          Files.createDirectories(fixture.root.resolve("private/empty"))

          fixture.assertInvalid(DistributionProblem.INVALID_LAYOUT)
        }
      }

      test("orphan dependency jars are rejected instead of being invented into a classpath") {
        withFixture { fixture ->
          DistributionFixture.writeJar(
            fixture.root.resolve("lib/orphan.jar"),
            mapOf("example/Orphan.class" to compiledClass("example.Orphan")),
          )
          fixture.reseal()

          fixture.assertInvalid(DistributionProblem.PROTOCOL_LAYOUT_INVALID)
        }
      }

      test("both exact installDist launchers are required") {
        withFixture { fixture ->
          fixture.deleteAndReseal(UNIX_LAUNCHER)

          fixture.assertInvalid(DistributionProblem.INVALID_LAYOUT)
        }
      }

      test("files outside the exact installDist launcher pair are rejected from bin") {
        withFixture { fixture ->
          fixture.addFileAndReseal("bin/unreviewed-launcher", "extra\n".encodeToByteArray())

          fixture.assertInvalid(DistributionProblem.INVALID_LAYOUT)
        }
      }

      test("every launcher byte is bound into protocol identity") {
        withFixture { fixture ->
          fixture.replaceFile(UNIX_LAUNCHER, "#!/bin/sh\nexit 7\n".encodeToByteArray())

          fixture.assertInvalid(DistributionProblem.PROTOCOL_HASH_MISMATCH)
        }
      }

      test("symlinks cannot escape the distribution root") {
        withFixture { fixture ->
          val outside = fixture.root.resolveSibling("outside.jar")
          Files.writeString(outside, "private")
          Files.createSymbolicLink(fixture.root.resolve("lib/outside.jar"), outside)

          fixture.assertInvalid(DistributionProblem.SYMBOLIC_LINK_NOT_ALLOWED)
        }
      }

      test("corrupt jars are rejected after their declared hashes are refreshed") {
        withFixture { fixture ->
          fixture.replaceFile(BENCHMARK_DEPENDENCY, "not-a-jar".encodeToByteArray(), true)

          fixture.assertInvalid(DistributionProblem.INVALID_JAR)
        }
      }

      test("jar entry CRC mismatches are rejected") {
        withFixture { fixture ->
          val jarPath = fixture.root.resolve(BENCHMARK_DEPENDENCY)
          val corrupted = corruptCentralDirectoryCrc(Files.readAllBytes(jarPath))
          fixture.replaceFile(BENCHMARK_DEPENDENCY, corrupted, true)

          fixture.assertInvalid(DistributionProblem.INVALID_JAR)
        }
      }

      test("class entries with corrupt bytes are rejected") {
        withFixture { fixture ->
          fixture.replaceJar(
            BENCHMARK_DEPENDENCY,
            mapOf("example/Corrupt.class" to byteArrayOf(1)),
          )

          fixture.assertInvalid(DistributionProblem.INVALID_JAR)
        }
      }

      test("untouched runner installDist dependencies survive jar tool API-difference status") {
        withFixture { fixture ->
          listOf(
              "tools.jackson.core:jackson-core:3.1.4" to "jackson-core-3.1.4.jar",
              "com.squareup.moshi:moshi:1.15.2" to "moshi-1.15.2.jar",
            )
            .forEach { (coordinate, fileName) ->
              val source = installDistLib().resolve(fileName)
              Files.isRegularFile(source) shouldBe true
              inProcessJarValidationExitCode(source) shouldNotBe 0

              val relativePath = "runner/lib/$fileName"
              fixture.addRunnerJar(relativePath, coordinate, source)
              Sha256.digest(Files.readAllBytes(fixture.root.resolve(relativePath))) shouldBe
                Sha256.digest(Files.readAllBytes(source))
            }

          fixture
            .validateBeforeProcess(ProcessSpy())
            .shouldBeInstanceOf<DistributionValidation.Valid>()
        }
      }

      listOf(PRODUCTION_JAR, BENCHMARK_JAR, RUNNER_JAR).forEach { projectJar ->
        test("project-built $projectJar requires in-process JDK jar validation") {
          withFixture { fixture ->
            val entries =
              when (projectJar) {
                BENCHMARK_JAR ->
                  mapOf(
                    "META-INF/BenchmarkList" to
                      fixture.benchmarkList(EXPECTED_BENCHMARK).encodeToByteArray(),
                    "META-INF/CompilerHints" to
                      "dontinline,example.Benchmark.measure\n".encodeToByteArray(),
                    "example/Benchmark.class" to
                      compiledClass("example.Actual", "public void measure() {}"),
                  )
                else ->
                  mapOf("example/Claimed.class" to compiledClass("example.Actual"))
              }
            fixture.replaceJar(projectJar, entries)
            inProcessJarValidationExitCode(fixture.root.resolve(projectJar)) shouldNotBe 0

            fixture.assertInvalid(DistributionProblem.INVALID_JAR)
          }
        }
      }

      mapOf(
          "BenchmarkList" to "META-INF/CompilerHints",
          "CompilerHints" to "META-INF/BenchmarkList",
        )
        .forEach { (missing, retained) ->
          test("benchmark jar missing META-INF/$missing is rejected") {
            withFixture { fixture ->
              val retainedBytes =
                when (retained) {
                  "META-INF/BenchmarkList" ->
                    fixture.benchmarkList(EXPECTED_BENCHMARK).encodeToByteArray()
                  else -> "dontinline,example.Benchmark.measure\n".encodeToByteArray()
                }
              fixture.replaceJar(
                BENCHMARK_JAR,
                mapOf(
                  retained to retainedBytes,
                  "example/Benchmark.class" to
                    compiledClass("example.Benchmark", "public void measure() {}"),
                ),
              )

              fixture.assertInvalid(DistributionProblem.BENCHMARK_METADATA_MISSING)
            }
          }
        }

      test("benchmark metadata rejects benchmarks outside the declared exact set") {
        withFixture { fixture ->
          fixture.replaceJar(
            BENCHMARK_JAR,
            mapOf(
              "META-INF/BenchmarkList" to
                (fixture.benchmarkList(EXPECTED_BENCHMARK) +
                    fixture.benchmarkList("example.Unexpected.measure"))
                  .encodeToByteArray(),
              "META-INF/CompilerHints" to "hints\n".encodeToByteArray(),
              "example/Benchmark.class" to
                compiledClass("example.Benchmark", "public void measure() {}"),
              "example/Unexpected.class" to
                compiledClass("example.Unexpected", "public void measure() {}"),
            ),
          )

          fixture.assertInvalid(DistributionProblem.UNEXPECTED_BENCHMARK)
        }
      }

      test("malformed benchmark metadata is rejected") {
        withFixture { fixture ->
          fixture.replaceJar(
            BENCHMARK_JAR,
            mapOf(
              "META-INF/BenchmarkList" to "not-jmh-metadata\n".encodeToByteArray(),
              "META-INF/CompilerHints" to "hints\n".encodeToByteArray(),
              "example/Benchmark.class" to
                compiledClass("example.Benchmark", "public void measure() {}"),
            ),
          )

          fixture.assertInvalid(DistributionProblem.INVALID_BENCHMARK_METADATA)
        }
      }

      test("test classes are rejected from an ordered runtime classpath") {
        withFixture { fixture ->
          fixture.replaceJar(
            BENCHMARK_DEPENDENCY,
            mapOf(
              "example/Dependency.class" to compiledClass("example.Dependency"),
              "example/DistributionValidatorTest.class" to
                compiledClass("example.DistributionValidatorTest"),
            ),
          )

          fixture.assertInvalid(DistributionProblem.TEST_CONTENT_PRESENT)
        }
      }

      listOf(
          "org.junit.jupiter:junit-jupiter-api" to "lib/junit-jupiter-api.jar",
          "io.kotest:kotest-runner-junit5" to "lib/kotest-runner-junit5.jar",
          "io.mockk:mockk" to "lib/mockk.jar",
          "net.bytebuddy:byte-buddy" to "lib/byte-buddy.jar",
        )
        .forEach { (coordinate, path) ->
          test("test-only dependency $coordinate is rejected") {
            withFixture { fixture ->
              fixture.addBenchmarkJar(
                relativePath = path,
                coordinate = coordinate,
                entries = mapOf("testonly/Dependency.class" to compiledClass("testonly.Dependency")),
              )

              fixture.assertInvalid(DistributionProblem.TEST_DEPENDENCY_PRESENT)
            }
          }
        }

      mapOf<String, (ObjectNode) -> Unit>(
          "wildcard" to { document ->
            document.classpath("benchmarkClasspath").firstObject().put("path", "lib/*.jar")
          },
          "absolute path" to { document ->
            document.classpath("benchmarkClasspath").firstObject().put("path", "/private/app.jar")
          },
          "parent traversal" to { document ->
            document.classpath("benchmarkClasspath").firstObject().put("path", "../app.jar")
          },
        )
        .forEach { (condition, mutate) ->
          test("$condition classpath entry is rejected") {
            withFixture { fixture ->
              fixture.mutateClasspath(mutate)

              fixture.assertInvalid(DistributionProblem.INVALID_CLASSPATH_ENTRY)
            }
          }
        }

      test("repeated classpath entries are rejected") {
        withFixture { fixture ->
          fixture.mutateClasspath { document ->
            val classpath = document.classpath("benchmarkClasspath")
            classpath.get(2).asObject().apply {
              put("path", PRODUCTION_JAR)
              put("sha256", classpath.get(1).get("sha256").asString())
            }
          }

          fixture.assertInvalid(DistributionProblem.DUPLICATE_CLASSPATH_ENTRY)
        }
      }

      test("repeated classpath coordinates are rejected") {
        withFixture { fixture ->
          fixture.mutateClasspath { document ->
            val classpath = document.classpath("benchmarkClasspath")
            classpath
              .get(2)
              .asObject()
              .put("coordinate", classpath.get(1).get("coordinate").asString())
          }

          fixture.assertInvalid(DistributionProblem.DUPLICATE_CLASSPATH_ENTRY)
        }
      }

      test("missing classpath artifacts are rejected without scanning lib to replace them") {
        withFixture { fixture ->
          fixture.deleteAndReseal(BENCHMARK_DEPENDENCY)

          fixture.assertInvalid(DistributionProblem.CLASSPATH_ENTRY_MISSING)
        }
      }

      test("declared classpath order numbers must match array order") {
        withFixture { fixture ->
          fixture.mutateClasspath { document ->
            document.classpath("benchmarkClasspath").get(0).asObject().put("order", 1)
          }

          fixture.assertInvalid(DistributionProblem.INVALID_CLASSPATH_ORDER)
        }
      }

      test("classpath hashes are independently checked after distribution checksum validation") {
        withFixture { fixture ->
          fixture.mutateClasspath { document ->
            document
              .classpath("benchmarkClasspath")
              .firstObject()
              .put("sha256", "0".repeat(64))
          }

          fixture.assertInvalid(DistributionProblem.CLASSPATH_HASH_MISMATCH)
        }
      }

      test("ordinary effective binary classes cannot be supplied by two jars") {
        withFixture { fixture ->
          fixture.replaceJar(
            BENCHMARK_DEPENDENCY,
            mapOf("example/Application.class" to compiledClass("example.Application")),
          )

          fixture.assertInvalid(DistributionProblem.DUPLICATE_EFFECTIVE_CLASS)
        }
      }

      test("malformed service provider declarations are rejected") {
        withFixture { fixture ->
          fixture.replaceJar(
            BENCHMARK_DEPENDENCY,
            mapOf(
              "META-INF/services/example.Service" to "not/a/provider\n".encodeToByteArray(),
              "example/Dependency.class" to compiledClass("example.Dependency"),
            ),
          )

          fixture.assertInvalid(DistributionProblem.INVALID_SERVICE_DESCRIPTOR)
        }
      }

      test("service providers must resolve to an effective class") {
        withFixture { fixture ->
          fixture.replaceJar(
            BENCHMARK_DEPENDENCY,
            mapOf(
              "META-INF/services/example.Service" to "example.MissingProvider\n".encodeToByteArray(),
              "example/Dependency.class" to compiledClass("example.Dependency"),
              "example/Service.class" to compiledClass("example.Service"),
            ),
          )

          fixture.assertInvalid(DistributionProblem.SERVICE_PROVIDER_MISSING)
        }
      }

      test("selected Java below feature version 21 is rejected") {
        withFixture { fixture ->
          val oldJava = fixture.selectedJava.copy(featureVersion = 20)

          fixture.assertInvalid(DistributionProblem.JAVA_VERSION_UNSUPPORTED, oldJava)
        }
      }

      test("selected Java must exactly match the declared executable identity") {
        withFixture { fixture ->
          val otherJava = fixture.selectedJava.copy(sha256 = Sha256.parse("0".repeat(64)))

          fixture.assertInvalid(DistributionProblem.JAVA_RUNTIME_MISMATCH, otherJava)
        }
      }

      test("selected Java executable must use the exact recorded absolute path") {
        withFixture { fixture ->
          val relativeExecutable =
            Path.of("").toAbsolutePath().relativize(fixture.selectedJava.executable)
          val relativeJava = fixture.selectedJava.copy(executable = relativeExecutable)

          fixture.assertInvalid(DistributionProblem.JAVA_RUNTIME_MISMATCH, relativeJava)
        }
      }

      test("a self-consistent hashed text file cannot stand in for the executing Java binary") {
        withFixture { fixture ->
          val textExecutable = fixture.root.resolveSibling("not-java")
          Files.writeString(textExecutable, "not a Java executable\n")
          textExecutable.toFile().setExecutable(false, false)
          Files.isExecutable(textExecutable) shouldBe false
          val forgedIdentity =
            JavaRuntimeIdentity(
              executable = textExecutable.toAbsolutePath().normalize(),
              featureVersion = Runtime.version().feature(),
              sha256 = Sha256.digest(Files.readAllBytes(textExecutable)),
            )
          fixture.declareJava(forgedIdentity)

          fixture.assertInvalid(DistributionProblem.JAVA_RUNTIME_MISMATCH, forgedIdentity)
        }
      }

      test("stale staging output is rejected before process execution") {
        withFixture { fixture ->
          Files.createDirectories(fixture.stagingOutput)
          Files.writeString(fixture.stagingOutput.resolve("stale.json"), "{}")

          fixture.assertInvalid(DistributionProblem.STAGING_OUTPUT_NOT_NEW)
        }
      }

      test("an existing empty staging directory is rejected because output must be new") {
        withFixture { fixture ->
          Files.createDirectories(fixture.stagingOutput)

          fixture.assertInvalid(DistributionProblem.STAGING_OUTPUT_NOT_NEW)
        }
      }

      mapOf(
          "runner" to DistributionProblem.RUNNER_HASH_MISMATCH,
          "adapter" to DistributionProblem.ADAPTER_HASH_MISMATCH,
          "schemas" to DistributionProblem.SCHEMA_HASH_MISMATCH,
          "profiles" to DistributionProblem.PROFILE_HASH_MISMATCH,
          "runtimeDeclarations" to DistributionProblem.RUNTIME_HASH_MISMATCH,
          "qualificationPolicies" to DistributionProblem.POLICY_HASH_MISMATCH,
          "expectedCells" to DistributionProblem.EXPECTED_CELLS_HASH_MISMATCH,
          "testVectors" to DistributionProblem.TEST_VECTOR_HASH_MISMATCH,
        )
        .forEach { (field, expectedProblem) ->
          test("$field binding is independently checked against frozen bytes") {
            withFixture { fixture ->
              fixture.mutateProtocol { protocol ->
                protocol.artifact(field).put("sha256", "0".repeat(64))
              }

              fixture.assertInvalid(expectedProblem)
            }
          }
        }

      test("caller-provided expected protocol identity must match") {
        withFixture { fixture ->
          fixture.assertInvalid(
            DistributionProblem.PROTOCOL_HASH_MISMATCH,
            expectedProtocolHash = Sha256.parse("0".repeat(64)),
          )
        }
      }

      test("declared protocol identity is always recomputed without an external expectation") {
        withFixture { fixture ->
          fixture.mutateProtocol { protocol -> protocol.put("protocolSha256", "0".repeat(64)) }

          fixture.assertInvalid(
            DistributionProblem.PROTOCOL_HASH_MISMATCH,
            expectedProtocolHash = null,
          )
        }
      }

      test("embedded Task 4 schemas must match the validator's reviewed bytes") {
        withFixture { fixture ->
          fixture.replaceFile(
            "protocol/schemas/distribution-classpath-v1.schema.json",
            "{}\n".encodeToByteArray(),
            refreshBindings = true,
          )

          fixture.assertInvalid(DistributionProblem.EMBEDDED_SCHEMA_MISMATCH)
        }
      }

      test("every frozen protocol schema must match the runner's reviewed bytes") {
        withFixture { fixture ->
          fixture.replaceFile(
            "protocol/schemas/capture-v1.schema.json",
            "{}\n".encodeToByteArray(),
            refreshBindings = true,
          )

          fixture.assertInvalid(DistributionProblem.EMBEDDED_SCHEMA_MISMATCH)
        }
      }

      test("unbound protocol schemas are rejected from the exact V1 layout") {
        withFixture { fixture ->
          fixture.addFileAndReseal(
            "protocol/schemas/unexpected-v1.schema.json",
            "{}\n".encodeToByteArray(),
          )

          fixture.assertInvalid(DistributionProblem.PROTOCOL_LAYOUT_INVALID)
        }
      }

      test("privacy-safe validation problems never expose host paths or rejected values") {
        withFixture { fixture ->
          val privateValue = "/Users/customer/private-distribution.jar"
          fixture.mutateClasspath { document ->
            document.classpath("benchmarkClasspath").firstObject().put("path", privateValue)
          }

          val invalid = fixture.invalid()
          val rendered = invalid.problems.joinToString(separator = "|")

          rendered shouldNotContain privateValue
          rendered shouldNotContain fixture.root.toString()
        }
      }
    },
  )

private class ProcessSpy {
  var requests: Int = 0
    private set
  var requestedRoot: Path? = null
    private set

  fun request(distribution: VerifiedDistribution) {
    requestedRoot = distribution.root
    requests += 1
  }
}

private fun DistributionFixture.validateBeforeProcess(processSpy: ProcessSpy): DistributionValidation =
  DistributionValidator().validate(request()).also { validation ->
    if (validation is DistributionValidation.Valid) {
      processSpy.request(validation.distribution)
    }
  }

private fun DistributionFixture.assertInvalid(
  expectedProblem: DistributionProblem,
  selectedJava: JavaRuntimeIdentity = this.selectedJava,
  expectedProtocolHash: Sha256? = protocolHash(),
) {
  val processSpy = ProcessSpy()
  val validation =
    DistributionValidator()
      .validate(
        request(
          selectedJava = selectedJava,
          expectedProtocolHash = expectedProtocolHash,
        ),
      )
      .also { result ->
        if (result is DistributionValidation.Valid) {
          processSpy.request(result.distribution)
        }
      }
  val invalid = validation as DistributionValidation.Invalid

  invalid.problems shouldContain expectedProblem
  processSpy.requests shouldBe 0
}

private fun DistributionFixture.invalid(): DistributionValidation.Invalid =
  ProcessSpy().let { processSpy ->
    val validation =
      DistributionValidator().validate(request()).also { result ->
        if (result is DistributionValidation.Valid) {
          processSpy.request(result.distribution)
        }
      }
    processSpy.requests shouldBe 0
    validation as DistributionValidation.Invalid
  }

private inline fun withFixture(block: (DistributionFixture) -> Unit) {
  val fixture = DistributionFixture.create()
  try {
    block(fixture)
  } finally {
    fixture.close()
  }
}

private fun ObjectNode.classpath(name: String): ArrayNode = get(name) as ArrayNode

private fun ArrayNode.firstObject(): ObjectNode = get(0).asObject()

private fun ObjectNode.artifact(field: String): ObjectNode =
  when (val value = get(field)) {
    is ArrayNode -> value.get(0).asObject()
    else -> value.asObject()
  }

private fun portablePath(path: Path): String =
  path.joinToString(separator = "/") { it.toString() }

private fun installDistLib(): Path =
  Path.of(
    checkNotNull(System.getProperty(INSTALL_DIST_LIB_PROPERTY)) {
      "missing installDist library path"
    },
  )

private fun inProcessJarValidationExitCode(path: Path): Int {
  val jarTool = checkNotNull(ToolProvider.findFirst("jar").orElse(null))
  return PrintWriter(Writer.nullWriter()).use { output ->
    PrintWriter(Writer.nullWriter()).use { error ->
      jarTool.run(output, error, "--validate", "--file", path.toString())
    }
  }
}

private fun corruptCentralDirectoryCrc(jarBytes: ByteArray): ByteArray =
  jarBytes.copyOf().also { corrupted ->
    val header =
      (0..corrupted.size - ZIP_CENTRAL_HEADER.size)
        .lastOrNull { offset ->
          ZIP_CENTRAL_HEADER.indices.all { index ->
            corrupted[offset + index] == ZIP_CENTRAL_HEADER[index]
          }
        }
    checkNotNull(header) { "fixture jar has no central-directory entry" }
    corrupted[header + ZIP_CRC_OFFSET] =
      (corrupted[header + ZIP_CRC_OFFSET].toInt() xor 1).toByte()
  }

private val ZIP_CENTRAL_HEADER = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
private const val ZIP_CRC_OFFSET = 16
private const val INSTALL_DIST_LIB_PROPERTY = "performance.runner.install-dist-lib"
