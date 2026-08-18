/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.finalize

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import performance.capture.CaptureOutcome
import performance.capture.CaptureProfileFamily
import performance.capture.CaptureRequest
import performance.capture.CaptureRunner
import performance.capture.testProfile
import performance.capture.validJmhBytes
import performance.capture.withVerifiedDistribution
import performance.compare.CaptureBundleVerifier
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.ArtifactIdentity
import performance.model.EvidenceStatus
import performance.model.HostDocumentRef
import performance.model.ProvisionalCaptureDocument
import performance.model.ProvisionalEvidenceStrength
import performance.model.ProvisionalOutcomeReason
import performance.model.QualificationEvidence
import performance.process.ProcessExecutor
import performance.process.ProcessResult
import performance.process.ProcessSpec

/**
 * Defects caught here: accepting caller-selected evidence strength, trusting provisional metadata,
 * sealing altered or privacy-unsafe operation bytes, publishing ambiguous layouts, and minting a
 * success result before the existing verifier accepts the exact sealed tree.
 */
class DiagnosticCaptureSealerTest :
  FunSpec(
    {
      test("a real cold capture seals as one exact verified diagnostic bundle") {
        withVerifiedDistribution { fixture, distribution ->
          val scenario = coldScenario(fixture.root.resolveSibling("seal-operation-1"), distribution)
          val bundleRoot = fixture.root.resolveSibling("seal-bundle-1")

          val sealed =
            DiagnosticCaptureSealer
              .seal(scenario.document, scenario.operationRoot, bundleRoot, scenario.qualification)
              .shouldBeInstanceOf<DiagnosticSealOutcome.Sealed>()

          sealed.root shouldBe bundleRoot
          Files.list(bundleRoot).use { entries ->
            entries.map { it.fileName.toString() }.sorted().toList()
          } shouldContainExactly
            listOf("capture.json", "checksums.sha256", "jmh-result.json", "stderr.log", "stdout.log")
          Files.readAllBytes(bundleRoot.resolve("jmh-result.json")) shouldBe
            Files.readAllBytes(scenario.operationRoot.resolve("jmh-result.json"))
          Files.readAllBytes(bundleRoot.resolve("stdout.log")) shouldBe
            Files.readAllBytes(scenario.operationRoot.resolve("stdout.log"))
          Files.readAllBytes(bundleRoot.resolve("stderr.log")) shouldBe
            Files.readAllBytes(scenario.operationRoot.resolve("stderr.log"))

          val verification = CaptureBundleVerifier.verify(bundleRoot)
          verification.failures shouldBe emptyList()
          val projection = checkNotNull(verification.projection)
          sealed.projection.root shouldBe projection.root
          sealed.projection.captureSha256 shouldBe projection.captureSha256
          sealed.projection.bundleSha256 shouldBe projection.bundleSha256
          projection.captureSha256 shouldBe sealed.captureSha256
          projection.bundleSha256 shouldBe sealed.manifestSha256
          projection.outcomeStatus shouldBe "valid"
          projection.qualificationKind shouldBe "controlledMacBoundedDiagnostic"
          projection.profilerSummaryPresent shouldBe false
          val capture = CanonicalJson.parseStrict(Files.readAllBytes(bundleRoot.resolve("capture.json")))
          capture.get("outcome").get("strength").asString() shouldBe "diagnostic"
          capture.get("outcome").get("claimEligibilityReasons").get(0).asString() shouldBe
            "boundedDiagnostic"
          capture.toString() shouldNotContain scenario.operationRoot.toString()
        }
      }

      test("equivalent verified inputs seal to byte-identical trees") {
        withVerifiedDistribution { fixture, distribution ->
          val first = coldScenario(fixture.root.resolveSibling("seal-operation-2a"), distribution)
          val second = coldScenario(fixture.root.resolveSibling("seal-operation-2b"), distribution)
          val firstRoot = fixture.root.resolveSibling("seal-bundle-2a")
          val secondRoot = fixture.root.resolveSibling("seal-bundle-2b")

          DiagnosticCaptureSealer
            .seal(first.document, first.operationRoot, firstRoot, first.qualification)
            .shouldBeInstanceOf<DiagnosticSealOutcome.Sealed>()
          DiagnosticCaptureSealer
            .seal(second.document, second.operationRoot, secondRoot, second.qualification)
            .shouldBeInstanceOf<DiagnosticSealOutcome.Sealed>()

          snapshot(firstRoot) shouldBe snapshot(secondRoot)
        }
      }

      test("malformed provisional state rejects before materializing output") {
        withVerifiedDistribution { fixture, distribution ->
          val mutations =
            listOf<Pair<String, (ProvisionalCaptureDocument) -> ProvisionalCaptureDocument>>(
              "wrong schema" to { it.copy(schemaVersion = "capture-v1") },
              "wrong protocol" to { it.copy(benchmarkProtocolVersion = "performance-v2") },
              "invalid status" to {
                it.copy(outcome = it.outcome.copy(status = EvidenceStatus.INVALID))
              },
              "canary strength" to {
                it.copy(outcome = it.outcome.copy(strength = ProvisionalEvidenceStrength.CANARY))
              },
              "claim-bearing reason" to {
                it.copy(
                  outcome =
                    it.outcome.copy(reasons = listOf(ProvisionalOutcomeReason.STRUCTURAL_CANARY)),
                )
              },
              "nonzero process exit" to { it.copy(outcome = it.outcome.copy(processExit = 3)) },
              "reversed timestamps" to {
                it.copy(
                  outcome =
                    it.outcome.copy(
                      startedAtUtc = "2026-08-18T00:00:01Z",
                      completedAtUtc = "2026-08-18T00:00:00Z",
                    ),
                )
              },
              "dirty provenance" to {
                it.copy(
                  provenance =
                    it.provenance.copy(
                      captureRunner = it.provenance.captureRunner.copy(treeClean = false),
                    ),
                )
              },
              "profile geometry mismatch" to {
                it.copy(profile = it.profile.copy(forks = it.profile.forks + 10))
              },
              "profiler capture" to {
                it.copy(profile = it.profile.copy(profiler = "gc"))
              },
              "cell row hash mismatch" to {
                val cell = it.cells.single()
                it.copy(
                  cells =
                    listOf(
                      cell.copy(
                        jmhResultRow =
                          cell.jmhResultRow.copy(sha256 = Sha256.parse("b".repeat(64))),
                      ),
                    ),
                )
              },
              "artifact hash mismatch" to {
                it.copy(
                  artifacts =
                    it.artifacts.copy(
                      production =
                        ArtifactIdentity(it.artifacts.production.path, Sha256.parse("c".repeat(64))),
                    ),
                )
              },
            )

          mutations.forEachIndexed { index, (name, mutate) ->
            val scenario = coldScenario(fixture.root.resolveSibling("seal-invalid-$index"), distribution)
            val bundleRoot = fixture.root.resolveSibling("seal-invalid-bundle-$index")

            withClue(name) {
              DiagnosticCaptureSealer
                .seal(mutate(scenario.document), scenario.operationRoot, bundleRoot, scenario.qualification)
                .shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()
              Files.exists(bundleRoot) shouldBe false
            }
          }
        }
      }

      test("qualification mismatch rejects before materializing output") {
        withVerifiedDistribution { fixture, distribution ->
          val scenario = coldScenario(fixture.root.resolveSibling("seal-qualification"), distribution)
          val wrongPolicy =
            scenario.qualification.copy(policyHash = Sha256.parse("d".repeat(64)))
          val bundleRoot = fixture.root.resolveSibling("seal-qualification-bundle")

          DiagnosticCaptureSealer
            .seal(scenario.document, scenario.operationRoot, bundleRoot, wrongPolicy)
            .shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()
          Files.exists(bundleRoot) shouldBe false
        }
      }

      test("altered canonical JMH and contaminated logs reject before materializing output") {
        withVerifiedDistribution { fixture, distribution ->
          val cases =
            listOf<Pair<String, (Path) -> Unit>>(
              "altered canonical JMH" to { root ->
                val rows = CanonicalJson.parseStrict(Files.readAllBytes(root.resolve("jmh-result.json")))
                rows.get(0).get("primaryMetric").get("rawData").get(0).asArray().set(0, 9.5)
                Files.write(root.resolve("jmh-result.json"), CanonicalJson.encode(rows))
              },
              "absolute host path in stdout" to { root ->
                Files.writeString(root.resolve("stdout.log"), "leak /Users/private/source.kt")
              },
              "environment secret in stderr" to { root ->
                Files.writeString(root.resolve("stderr.log"), "ACCESS_TOKEN=secret-value")
              },
            )

          cases.forEachIndexed { index, (name, contaminate) ->
            val scenario = coldScenario(fixture.root.resolveSibling("seal-content-$index"), distribution)
            contaminate(scenario.operationRoot)
            val bundleRoot = fixture.root.resolveSibling("seal-content-bundle-$index")

            withClue(name) {
              DiagnosticCaptureSealer
                .seal(scenario.document, scenario.operationRoot, bundleRoot, scenario.qualification)
                .shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()
              Files.exists(bundleRoot) shouldBe false
            }
          }
        }
      }

      test("privacy-unsafe capture metadata rejects before materializing output") {
        withVerifiedDistribution { fixture, distribution ->
          val mutations =
            listOf<Pair<String, (SealScenario) -> SealScenario>>(
              "additional secret environment variable" to { scenario ->
                scenario.copy(
                  document =
                    scenario.document.copy(
                      runtime =
                        scenario.document.runtime.copy(
                          environment =
                            scenario.document.runtime.environment + ("ACCESS_TOKEN" to "secret-value"),
                        ),
                    ),
                )
              },
              "secret path JVM argument" to { scenario ->
                scenario.copy(
                  document =
                    scenario.document.copy(
                      runtime =
                        scenario.document.runtime.copy(
                          jdk =
                            scenario.document.runtime.jdk.copy(
                              jvmArguments = listOf("-Dapi_key=/Users/private/secret"),
                            ),
                        ),
                    ),
                )
              },
              "host document path leak" to { scenario ->
                scenario.copy(
                  qualification =
                    scenario.qualification.copy(
                      preflight = scenario.qualification.preflight.copy(path = "/Users/private/preflight.json"),
                    ),
                )
              },
            )

          mutations.forEachIndexed { index, (name, mutate) ->
            val scenario =
              mutate(coldScenario(fixture.root.resolveSibling("seal-privacy-$index"), distribution))
            val bundleRoot = fixture.root.resolveSibling("seal-privacy-bundle-$index")

            withClue(name) {
              DiagnosticCaptureSealer
                .seal(scenario.document, scenario.operationRoot, bundleRoot, scenario.qualification)
                .shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()
              Files.exists(bundleRoot) shouldBe false
            }
          }
        }
      }

      test("extra symlink directory raw and profiler inputs reject before materializing output") {
        withVerifiedDistribution { fixture, distribution ->
          val cases =
            listOf<Pair<String, (Path) -> Unit>>(
              "extra file" to { Files.writeString(it.resolve("extra.txt"), "extra") },
              "symlink" to { Files.createSymbolicLink(it.resolve("linked.log"), it.resolve("stdout.log")) },
              "directory" to { Files.createDirectory(it.resolve("nested")) },
              "raw JMH" to { Files.writeString(it.resolve("jmh-result.raw.json"), "[]") },
              "profiler summary" to { Files.writeString(it.resolve("profiler-summary.json"), "{}") },
              "raw profiler" to { Files.write(it.resolve("profile.jfr"), byteArrayOf(1)) },
            )

          cases.forEachIndexed { index, (name, addInput) ->
            val scenario = coldScenario(fixture.root.resolveSibling("seal-layout-$index"), distribution)
            addInput(scenario.operationRoot)
            val bundleRoot = fixture.root.resolveSibling("seal-layout-bundle-$index")

            withClue(name) {
              DiagnosticCaptureSealer
                .seal(scenario.document, scenario.operationRoot, bundleRoot, scenario.qualification)
                .shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()
              Files.exists(bundleRoot) shouldBe false
            }
          }
        }
      }

      test("a FIFO input rejects before materializing output on supported Unix platforms") {
        if (!supportedUnix()) return@test
        withVerifiedDistribution { fixture, distribution ->
          val scenario = coldScenario(fixture.root.resolveSibling("seal-fifo"), distribution)
          val fifo = scenario.operationRoot.resolve("unexpected.fifo")
          ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() shouldBe 0
          val bundleRoot = fixture.root.resolveSibling("seal-fifo-bundle")

          DiagnosticCaptureSealer
            .seal(scenario.document, scenario.operationRoot, bundleRoot, scenario.qualification)
            .shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()
          Files.exists(bundleRoot) shouldBe false
        }
      }

      test("unsafe paths and pre-existing output reject without changing the destination") {
        withVerifiedDistribution { fixture, distribution ->
          val scenario = coldScenario(fixture.root.resolveSibling("seal-paths"), distribution)
          val parent = scenario.operationRoot.parent
          val nonNormalizedOperation = parent.resolve("child").resolve("..").resolve(scenario.operationRoot.fileName)
          val firstBundle = fixture.root.resolveSibling("seal-path-bundle-1")
          DiagnosticCaptureSealer
            .seal(scenario.document, nonNormalizedOperation, firstBundle, scenario.qualification)
            .shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()
          Files.exists(firstBundle) shouldBe false

          val nonNormalizedBundle =
            fixture.root.resolveSibling("unused").resolve("..").resolve("seal-path-bundle-2")
          DiagnosticCaptureSealer
            .seal(scenario.document, scenario.operationRoot, nonNormalizedBundle, scenario.qualification)
            .shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()
          Files.exists(nonNormalizedBundle.normalize()) shouldBe false

          val existing = fixture.root.resolveSibling("seal-path-bundle-existing")
          Files.createDirectory(existing)
          Files.writeString(existing.resolve("sentinel"), "unchanged")
          DiagnosticCaptureSealer
            .seal(scenario.document, scenario.operationRoot, existing, scenario.qualification)
            .shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()
          Files.readString(existing.resolve("sentinel")) shouldBe "unchanged"

          val nestedBundle = scenario.operationRoot.resolve("nested-bundle")
          DiagnosticCaptureSealer
            .seal(scenario.document, scenario.operationRoot, nestedBundle, scenario.qualification)
            .shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()
          Files.exists(nestedBundle) shouldBe false

          val containingBundle = scenario.operationRoot.parent
          DiagnosticCaptureSealer
            .seal(scenario.document, scenario.operationRoot, containingBundle, scenario.qualification)
            .shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()
        }
      }

      test("the interface exposes no canonical selector or forgeable verified result") {
        val seal = DiagnosticCaptureSealer::class.java.declaredMethods.single {
          it.name == "seal" && it.parameterCount == 4
        }
        seal.parameterTypes.none { it.name.contains("EvidenceStrength") } shouldBe true
        seal.parameterCount shouldBe 4
        DiagnosticSealOutcome.Sealed::class.java.isInterface shouldBe false
        DiagnosticSealOutcome.Sealed::class.java.declaredConstructors.all { constructor ->
          constructor.parameterTypes.any { it == CaptureBundleVerifier.Projection::class.java }
        } shouldBe true
      }

      test("checkpoint failures clean only owned staging and keep the target absent") {
        withVerifiedDistribution { fixture, distribution ->
          DiagnosticSealPoint.entries.forEachIndexed { index, failurePoint ->
            val scenario = coldScenario(fixture.root.resolveSibling("seal-crash-$index"), distribution)
            val bundleRoot = fixture.root.resolveSibling("seal-crash-bundle-$index")

            DiagnosticCaptureSealer
              .seal(
                scenario.document,
                scenario.operationRoot,
                bundleRoot,
                scenario.qualification,
                { point ->
                  if (point == failurePoint) error("injected failure at $point")
                },
              ).shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()
            Files.exists(bundleRoot) shouldBe false
            stagingEntries(bundleRoot) shouldBe emptyList()
          }
        }
      }

      test("a destination created at the pre-move checkpoint is retained byte-identically") {
        withVerifiedDistribution { fixture, distribution ->
          val scenario = coldScenario(fixture.root.resolveSibling("seal-race"), distribution)
          val bundleRoot = fixture.root.resolveSibling("seal-race-bundle")
          val sentinel = byteArrayOf(0, 1, 2, 3, -1)

          DiagnosticCaptureSealer
            .seal(
              scenario.document,
              scenario.operationRoot,
              bundleRoot,
              scenario.qualification,
              { point ->
                if (point == DiagnosticSealPoint.BEFORE_MOVE) {
                  Files.createDirectory(bundleRoot)
                  Files.write(bundleRoot.resolve("sentinel"), sentinel)
                }
              },
            ).shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()

          Files.readAllBytes(bundleRoot.resolve("sentinel")) shouldBe sentinel
          Files.list(bundleRoot).use { it.map(Path::getFileName).map(Path::toString).toList() } shouldBe
            listOf("sentinel")
          stagingEntries(bundleRoot) shouldBe emptyList()
        }
      }

      test("the full staging verifier rejects a resealed JMH parameter mismatch before visibility") {
        withVerifiedDistribution { fixture, distribution ->
          val scenario = coldScenario(fixture.root.resolveSibling("seal-jmh-mismatch"), distribution)
          val rows = CanonicalJson.parseStrict(Files.readAllBytes(scenario.operationRoot.resolve("jmh-result.json")))
          rows.get(0).get("params").asObject().put("case", "mismatched")
          val changedRow = CanonicalJson.encode(rows.get(0))
          Files.write(scenario.operationRoot.resolve("jmh-result.json"), CanonicalJson.encode(rows))
          val document =
            scenario.document.copy(
              cells =
                listOf(
                  scenario.document.cells.single().copy(
                    jmhResultRow =
                      scenario.document.cells.single().jmhResultRow.copy(
                        sha256 = Sha256.digest(changedRow),
                      ),
                  ),
                ),
            )
          val bundleRoot = fixture.root.resolveSibling("seal-jmh-mismatch-bundle")
          var verifierReached = false

          DiagnosticCaptureSealer
            .seal(
              document,
              scenario.operationRoot,
              bundleRoot,
              scenario.qualification,
              { point ->
                if (point == DiagnosticSealPoint.BEFORE_VERIFIER) {
                  verifierReached = true
                  Files.exists(bundleRoot) shouldBe false
                }
              },
            ).shouldBeInstanceOf<DiagnosticSealOutcome.Rejected>()

          verifierReached shouldBe true
          Files.exists(bundleRoot) shouldBe false
          stagingEntries(bundleRoot) shouldBe emptyList()
        }
      }
    },
  )

private data class SealScenario(
  val document: ProvisionalCaptureDocument,
  val operationRoot: Path,
  val qualification: QualificationEvidence.ControlledMacBoundedDiagnostic,
)

private fun coldScenario(
  operationRoot: Path,
  distribution: performance.distribution.VerifiedDistribution,
): SealScenario {
  val executor =
    ProcessExecutor { spec: ProcessSpec ->
      Files.write(spec.resultPath, validJmhBytes(forks = 10))
      Files.writeString(spec.stdoutPath, "capture complete\n")
      Files.writeString(spec.stderrPath, "")
      ProcessResult(0)
    }
  val profile = testProfile(distribution, family = CaptureProfileFamily.COLD)
  val schemaCompatibleProfile =
    profile.copy(
      evidence =
        profile.evidence.copy(
          runtime =
            profile.evidence.runtime.copy(
              jdk = profile.evidence.runtime.jdk.copy(vendor = "Eclipse Adoptium"),
              environment = profile.evidence.runtime.environment + ("LC_ALL" to "C.UTF-8"),
            ),
        ),
    )
  val runnerDocument =
    CaptureRunner(executor, Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC))
      .capture(
        CaptureRequest(
          distribution = distribution,
          profile = schemaCompatibleProfile,
          identity =
            performance.model.CaptureIdentity(
              captureId = "capture-fixed",
              processRunId = "process-fixed",
              performanceSessionId = "session-fixed",
              sessionSequence = 1,
            ),
          provisionalRoot = operationRoot,
        ),
      )
      .shouldBeInstanceOf<CaptureOutcome.Provisional>()
      .document
  val document =
    runnerDocument.copy(
      runtime =
        runnerDocument.runtime.copy(
          jdk =
            runnerDocument.runtime.jdk.copy(
              binarySha256 = Sha256.parse(FROZEN_JDK_SHA256),
              jvmArguments = listOf("-Xms2g", "-Xmx2g"),
            ),
        ),
    )
  val qualification =
    QualificationEvidence.ControlledMacBoundedDiagnostic(
      policyHash = document.protocol.qualificationPolicySha256,
      preflight = hostRef("preflight"),
      watcher = hostRef("watcher"),
      postflight = hostRef("postflight"),
      restoration = hostRef("restoration"),
      campaignFieldsInapplicableReason = "standaloneBoundedDiagnostic",
    )
  return SealScenario(document, operationRoot, qualification)
}

private fun hostRef(name: String): HostDocumentRef =
  HostDocumentRef("host/$name.json", Sha256.parse("a".repeat(64)))

private fun snapshot(root: Path): Map<String, List<Byte>> =
  Files.list(root).use { entries ->
    entries
      .sorted(compareBy { it.fileName.toString() })
      .toList()
      .associate { path -> path.fileName.toString() to Files.readAllBytes(path).toList() }
  }

private fun stagingEntries(bundleRoot: Path): List<String> =
  Files.list(bundleRoot.parent).use { entries ->
    entries
      .map(Path::getFileName)
      .map(Path::toString)
      .filter { it.startsWith(".${bundleRoot.fileName}.staging-") }
      .sorted()
      .toList()
  }

private fun supportedUnix(): Boolean =
  System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }

private const val FROZEN_JDK_SHA256 =
  "1cedc51a4102638f1f06077acb3611b88f3061f9c7d76bd0a0df7f8607a9367b"
