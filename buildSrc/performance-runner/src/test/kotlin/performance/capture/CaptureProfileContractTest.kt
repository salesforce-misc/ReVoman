/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import performance.json.CanonicalJson
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind

class CaptureProfileContractTest :
  FunSpec(
    {
      test("checked-in expected cells and all three profile families satisfy their strict schemas") {
        val root = repositoryRoot()
        val validator = EvidenceSchemaValidator()
        val expectedBytes = Files.readAllBytes(root.resolve("config/performance/expected-cells.json"))
        validator
          .validate(
            SchemaKind.EXPECTED_CELLS,
            CanonicalJson.encode(CanonicalJson.parseStrict(expectedBytes)),
          )
          .shouldBeEmpty()

        CaptureProfileFamily.entries.forEach { family ->
          val bytes =
            Files.readAllBytes(root.resolve("config/performance/profiles/${family.id}.json"))
          validator
            .validate(
              SchemaKind.CAPTURE_PROFILE_FAMILY,
              CanonicalJson.encode(CanonicalJson.parseStrict(bytes)),
            )
            .shouldBeEmpty()
          ExpectedCellsReader.read(expectedBytes, family).cells.isEmpty() shouldBe false
        }
      }

      test("strict profile schema accepts the exact writable home and packaged logging resource") {
        val root = repositoryRoot()
        val desiredProfile =
          Files.readString(root.resolve("config/performance/profiles/canary.json"))
            .replace(
              """    "-Duser.timezone=UTC",
    "-Dlog4j.configurationFile=classpath:log4j2-performance.xml",""",
              """    "-Duser.timezone=UTC",
    "-Duser.home=/operation/tmp",
    "-Dlog4j.configurationFile=classpath:performance/log4j2-performance.xml",""",
            )
        val canonical = CanonicalJson.encode(CanonicalJson.parseStrict(desiredProfile.encodeToByteArray()))

        EvidenceSchemaValidator()
          .validate(SchemaKind.CAPTURE_PROFILE_FAMILY, canonical)
          .shouldBeEmpty()
      }

      test("frozen profile families expose exactly the approved variant ladder") {
        val root = repositoryRoot()
        val expectedBytes = Files.readAllBytes(root.resolve("config/performance/expected-cells.json"))
        withVerifiedDistribution { _, distribution ->
          val context = testProfile(distribution).evidence
          val profiles =
            CaptureProfileFamily.entries.associateWith { family ->
              CaptureProfileReader.read(
                bytes =
                  Files.readAllBytes(root.resolve("config/performance/profiles/${family.id}.json")),
                expectedCells = ExpectedCellsReader.read(expectedBytes, family),
                expectedProtocolSha256 = distribution.metadata.protocol.protocolSha256,
                selectedJavaExecutable =
                  distribution.metadata.classpath.javaRuntime.executable,
                selectedJavaSha256 =
                  distribution.metadata.classpath.javaRuntime.executableSha256,
                evidence = context.copy(
                  protocol =
                    context.protocol.copy(
                      benchmarkProtocolSha256 = distribution.metadata.protocol.protocolSha256,
                    ),
                  runtime =
                    context.runtime.copy(
                      jdk =
                        context.runtime.jdk.copy(
                          binarySha256 =
                            distribution.metadata.classpath.javaRuntime.executableSha256,
                        ),
                    ),
                ),
              )
            }

          profiles.getValue(CaptureProfileFamily.CANARY).map { it.forks } shouldBe listOf(1)
          profiles.getValue(CaptureProfileFamily.COLD).map { it.forks } shouldContainExactlyInAnyOrder
            listOf(10, 20, 40)
          profiles
            .getValue(CaptureProfileFamily.WARM)
            .map { it.forks to it.profiler } shouldContainExactlyInAnyOrder
            listOf(
              10 to DiagnosticProfiler.NONE,
              20 to DiagnosticProfiler.NONE,
              40 to DiagnosticProfiler.NONE,
              10 to DiagnosticProfiler.GC,
              20 to DiagnosticProfiler.GC,
              40 to DiagnosticProfiler.GC,
              10 to DiagnosticProfiler.JFR,
              20 to DiagnosticProfiler.JFR,
              40 to DiagnosticProfiler.JFR,
            )
          profiles
            .getValue(CaptureProfileFamily.WARM)
            .filter { it.profiler == DiagnosticProfiler.JFR }
            .forEach { profile ->
              profile.profilerArguments shouldBe
                listOf(
                  "jfr:dir={operationRoot};configName=profile;debugNonSafePoints=true;stackDepth=1024;postProcessor=$JFR_FORK_ACCUMULATOR;verbose=false",
                )
            }
          profiles.values.flatten().map { it.variantSha256 }.distinct().size shouldBe 13
          profiles.values.flatten().forEach { profile ->
            profile.jvmArguments shouldBe APPROVED_JVM_ARGUMENTS
            val loggingResource =
              profile.jvmArguments
                .single { it.startsWith(LOGGING_CONFIGURATION_PREFIX) }
                .substringAfter("classpath:")
            Files.isRegularFile(root.resolve("src/jmh/resources").resolve(loggingResource)) shouldBe true
          }
        }
      }

      test("JFR variants require the frozen fork accumulator") {
        withVerifiedDistribution { _, distribution ->
          val profile =
            testProfile(distribution, CaptureProfileFamily.WARM, DiagnosticProfiler.JFR)

          profile.isStructurallyValid() shouldBe true
          profile
            .copy(
              profilerArguments =
                listOf(
                  "jfr:dir={operationRoot};configName=profile;debugNonSafePoints=true;stackDepth=1024;verbose=false",
                ),
            )
            .isStructurallyValid() shouldBe false
        }
      }

      test("profile and expected-cell schemas reject unknown protocol fields") {
        val root = repositoryRoot()
        val validator = EvidenceSchemaValidator()
        mapOf(
            SchemaKind.CAPTURE_PROFILE_FAMILY to "config/performance/profiles/canary.json",
            SchemaKind.EXPECTED_CELLS to "config/performance/expected-cells.json",
          )
          .forEach { (schema, relativePath) ->
            val document =
              CanonicalJson.parseStrict(Files.readAllBytes(root.resolve(relativePath))).asObject()
            document.put("unexpected", true)

            validator.validate(schema, CanonicalJson.encode(document)).shouldNotBeEmpty()
          }
      }

      test("expected-cell collections snapshot caller-owned mutable inputs") {
        val mutableParameters = mutableMapOf("scenario" to "v3-real-wire")
        val mutableCells = mutableListOf(ExpectedCell("example.Benchmark.measure", mutableParameters))
        val expected = ExpectedCells(mutableCells)

        mutableParameters["scenario"] = "mutated"
        mutableCells.clear()

        expected.cells.single().parameters shouldBe mapOf("scenario" to "v3-real-wire")
        shouldThrow<UnsupportedOperationException> {
          (expected.cells as MutableList<ExpectedCell>).clear()
        }
        shouldThrow<UnsupportedOperationException> {
          (expected.cells.single().parameters as MutableMap<String, String>)["new"] = "value"
        }
      }

      test("expected cells must be exactly representable by global JMH CLI parameters") {
        withVerifiedDistribution { _, distribution ->
          val conflicting =
            ExpectedCells(
              listOf(
                ExpectedCell("example.FirstBenchmark.work", mapOf("size" to "1")),
                ExpectedCell("example.SecondBenchmark.work", mapOf("size" to "2")),
              ),
            )
          testProfile(distribution, expectedCells = conflicting).isStructurallyValid() shouldBe false

          val cartesian =
            ExpectedCells(
              listOf("1", "2").flatMap { size ->
                listOf("a", "b").map { flavor ->
                  ExpectedCell(
                    "example.CartesianBenchmark.work",
                    mapOf("flavor" to flavor, "size" to size),
                  )
                }
              },
            )
          testProfile(distribution, expectedCells = cartesian).isStructurallyValid() shouldBe true
        }
      }

      test("expected-cell values reject JMH CLI comma splitting in code and schema") {
        shouldThrow<IllegalArgumentException> {
          ExpectedCell("example.Benchmark.measure", mapOf("scenario" to "first,second"))
        }

        val invalid =
          Files.readString(repositoryRoot().resolve("config/performance/expected-cells.json"))
            .replace("v3-real-wire", "v3,real-wire")
            .encodeToByteArray()
        EvidenceSchemaValidator().validate(SchemaKind.EXPECTED_CELLS, invalid).shouldNotBeEmpty()
      }
    },
  )

private fun repositoryRoot(): Path =
  generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(), Path::getParent)
    .first { Files.isDirectory(it.resolve("config/performance")) }

private const val LOGGING_CONFIGURATION_PREFIX = "-Dlog4j.configurationFile=classpath:"
private val APPROVED_JVM_ARGUMENTS =
  listOf(
    "-Xms2g",
    "-Xmx2g",
    "-Dfile.encoding=UTF-8",
    "-Duser.timezone=UTC",
    "-Duser.home=/operation/tmp",
    "-Dlog4j.configurationFile=classpath:performance/log4j2-performance.xml",
    "-Drevoman.banner=false",
  )
