/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.finalize

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import performance.compare.CaptureBundleVerifier
import performance.compare.CaptureComparator
import performance.compare.ComparisonComputation
import performance.compare.ComparisonKind
import performance.compare.ComparisonRequest
import performance.compare.PolicyOutcome
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.CampaignAttemptRecord
import performance.model.CampaignCandidateRecord
import performance.model.CampaignCaptureRecord
import performance.model.CampaignDocument
import performance.model.CampaignFileFact
import performance.model.CampaignImplementationRecord
import performance.model.CampaignQualificationRecord
import performance.model.CampaignReceiptRecord
import performance.model.CampaignStatus
import performance.model.HostDocumentRef
import performance.runner.RunnerExit
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

internal object CampaignMaterializer {
  @JvmSynthetic
  fun materialize(
    permit: CampaignMaterializationPermit,
    verified: VerifiedCampaignInput,
    comparator: CaptureComparator,
  ): MaterializedCampaign {
    val root = verified.request.outputRoot
    Files.createDirectory(root)
    try {
      val hostRefs = writeHostDocuments(root, verified)
      val selectedId = verified.request.selectedAttemptId
      val attemptRecords = mutableListOf<CampaignAttemptRecord>()
      var candidateResult: ComparisonComputation.Completed? = null
      verified.attempts.forEach { attempt ->
        val selected = attempt.input.attemptId == selectedId
        val a1 = writeCapture(root, attempt.a1, attempt.input.forks, "a1", selected, verified, hostRefs)
        val a2 = writeCapture(root, attempt.a2, attempt.input.forks, "a2", selected, verified, hostRefs)
        val b = attempt.b?.let { writeCapture(root, it, attempt.input.forks, "b", selected, verified, hostRefs) }
        val calibration =
          if (selected) {
            completed(
              comparator.compare(
                ComparisonRequest(
                  runnerDistribution = verified.baselineDistribution.root,
                  kind = ComparisonKind.CALIBRATION,
                  baseline = a1.projection.root,
                  candidate = a2.projection.root,
                ),
              ),
            ).also { require(it.document.calibration?.passed == true) }
          } else {
            requireNotNull(attempt.calibration)
          }
        val calibrationPath = "comparisons/${attempt.input.forks}-calibration"
        val calibrationBundle = writeComparison(root.resolve(calibrationPath), calibration.jsonBytes, calibration.markdownBytes)
        if (selected) {
          val selectedB = requireNotNull(b)
          val candidate =
            completed(
              comparator.compare(
                ComparisonRequest(
                  runnerDistribution = verified.baselineDistribution.root,
                  kind = ComparisonKind.CANDIDATE,
                  baseline = a2.projection.root,
                  candidate = selectedB.projection.root,
                  calibration = root.resolve(calibrationPath),
                  regressionPolicy = verified.request.regressionPolicy,
                ),
              ),
            )
          candidateResult = candidate
          val canonicalJson = canonicalComparison(candidate.jsonBytes)
          val canonicalMarkdown =
            candidate.markdownBytes.decodeToString().replace(
              "- Strength: `diagnostic`",
              "- Strength: `canonical`",
            ).encodeToByteArray()
          writeComparison(root.resolve(CANDIDATE_COMPARISON), canonicalJson, canonicalMarkdown)
        }
        attemptRecords +=
          CampaignAttemptRecord(
            attemptId = attempt.input.attemptId,
            forks = attempt.input.forks,
            a1 = a1.record,
            a2 = a2.record,
            b = b?.record,
            receipts =
              listOfNotNull(attempt.input.a1, attempt.input.a2, attempt.input.b).map { capture ->
                capture.receipt.record(if (capture.role == performance.campaign.CaptureRole.CANDIDATE_B) "candidate" else "baseline")
              },
            calibrationPath = calibrationPath,
            calibrationBundleSha256 = calibrationBundle,
            calibrationPassed = calibration.document.calibration?.passed == true,
          )
      }
      val status = if (selectedId == null) CampaignStatus.EXHAUSTED else CampaignStatus.QUALIFIED
      val implementationSource = candidateResult ?: requireNotNull(verified.attempts.last().calibration)
      val implementation = implementationSource.document.implementation
      val candidateRecord = candidateResult?.let { candidate ->
        CampaignCandidateRecord(
          path = CANDIDATE_COMPARISON,
          bundleSha256 = Sha256.digest(root.resolve(CANDIDATE_COMPARISON).resolve(CHECKSUMS)),
          policySha256 = candidate.document.policy.sha256,
          policyOutcome = candidate.document.policy.outcome.wire(),
          exit = candidate.exit,
        )
      }
      val campaign =
        CampaignDocument(
          campaignId = verified.request.campaignId,
          performanceSessionId = verified.request.performanceSessionId,
          attempts = attemptRecords,
          selectedAttemptId = selectedId,
          candidate = candidateRecord,
          qualification =
            CampaignQualificationRecord(
              verified.request.qualification.policyHash,
              hostRefs.preflight,
              hostRefs.watcher,
              hostRefs.postflight,
              hostRefs.restoration,
            ),
          implementation =
            CampaignImplementationRecord(
              implementation.runnerSha256,
              implementation.protocolSha256,
              implementation.adapterSha256,
              implementation.comparatorSha256,
              implementation.rendererSha256,
            ),
          status = status,
        )
      val campaignBytes = CampaignRenderer.render(permit, campaign)
      require(EvidenceSchemaValidator().validate(SchemaKind.CAMPAIGN, campaignBytes).isEmpty())
      Files.write(root.resolve(CAMPAIGN_JSON), campaignBytes)
      val manifestBytes = recursiveManifest(root)
      Files.write(root.resolve(CHECKSUMS), manifestBytes)
      val exit = if (status == CampaignStatus.EXHAUSTED) RunnerExit.CALIBRATION_FAILED else requireNotNull(candidateResult).exit
      return MaterializedCampaign(
        MATERIALIZED_CAMPAIGN_MINT,
        root,
        Sha256.digest(campaignBytes),
        Sha256.digest(manifestBytes),
        exit,
      )
    } catch (failure: Exception) {
      root.toFile().deleteRecursively()
      throw failure
    }
  }

  private fun writeCapture(
    root: Path,
    source: CaptureBundleVerifier.Projection,
    forks: Int,
    role: String,
    canonical: Boolean,
    verified: VerifiedCampaignInput,
    hostRefs: OutputHostRefs,
  ): MaterializedCapture {
    val relative = "captures/$forks-$role"
    val target = root.resolve(relative)
    Files.createDirectories(target.parent)
    copyTree(source.root, target)
    if (canonical) canonicalizeCapture(target, verified, hostRefs)
    val projection = CaptureBundleVerifier.verify(target).let { verification ->
      require(verification.failures.isEmpty())
      requireNotNull(verification.projection)
    }
    return MaterializedCapture(
      projection,
      CampaignCaptureRecord(
        role = role.uppercase(),
        path = relative,
        captureId = projection.identity.captureId,
        processRunId = projection.identity.processRunId,
        sequence = projection.identity.sessionSequence,
        captureSha256 = projection.captureSha256,
        bundleSha256 = projection.bundleSha256,
      ),
    )
  }

  private fun canonicalizeCapture(
    root: Path,
    verified: VerifiedCampaignInput,
    hostRefs: OutputHostRefs,
  ) {
    val capturePath = root.resolve(CAPTURE_JSON)
    val document = CanonicalJson.parseStrict(Files.readAllBytes(capturePath)).asObject()
    document.objectNode("outcome").apply {
      put("strength", "canonical")
      set("claimEligibilityReasons", JsonNodeFactory.instance.arrayNode().add("controlledMacCampaignQualified"))
    }
    document.set(
      "qualification",
      JsonNodeFactory.instance.objectNode().apply {
        put("cleanupPassed", true)
        put("kind", "controlledMacCampaign")
        put("policyHash", verified.request.qualification.policyHash.hex)
        set("postflight", hostRefs.postflight.refJson())
        set("preflight", hostRefs.preflight.refJson())
        set("restoration", hostRefs.restoration.refJson())
        set("watcher", hostRefs.watcher.refJson())
      },
    )
    val bytes = CanonicalJson.encode(document)
    require(EvidenceSchemaValidator().validate(SchemaKind.CAPTURE, bytes).isEmpty())
    Files.write(capturePath, bytes)
    Files.write(root.resolve(CHECKSUMS), flatManifest(root))
  }

  private fun writeHostDocuments(root: Path, verified: VerifiedCampaignInput): OutputHostRefs {
    val host = Files.createDirectory(root.resolve("host"))
    fun write(name: String, bytes: ByteArray): HostDocumentRef {
      Files.write(host.resolve(name), bytes)
      return HostDocumentRef("host/$name", Sha256.digest(bytes))
    }
    return OutputHostRefs(
      write("preflight.json", verified.hostDocuments.preflight),
      write("watcher.json", verified.hostDocuments.watcher),
      write("postflight.json", verified.hostDocuments.postflight),
      write("restoration.json", verified.hostDocuments.restoration),
    )
  }

  private fun writeComparison(root: Path, json: ByteArray, markdown: ByteArray): Sha256 {
    require(EvidenceSchemaValidator().validate(SchemaKind.COMPARISON, json).isEmpty())
    Files.createDirectories(root)
    Files.write(root.resolve(COMPARISON_JSON), json)
    Files.write(root.resolve(COMPARISON_MD), markdown)
    val manifest = flatManifest(root)
    Files.write(root.resolve(CHECKSUMS), manifest)
    return Sha256.digest(manifest)
  }

  private fun canonicalComparison(bytes: ByteArray): ByteArray {
    val document = CanonicalJson.parseStrict(bytes).asObject()
    document.put("strength", "canonical")
    return CanonicalJson.encode(document).also {
      require(EvidenceSchemaValidator().validate(SchemaKind.COMPARISON, it).isEmpty())
    }
  }

  private fun copyTree(source: Path, target: Path) {
    Files.walk(source).use { paths ->
      paths.forEach { path ->
        require(!Files.isSymbolicLink(path))
        val destination = target.resolve(source.relativize(path).toString())
        when {
          path == source -> Files.createDirectory(destination)
          Files.isDirectory(path, NOFOLLOW_LINKS) -> Files.createDirectory(destination)
          Files.isRegularFile(path, NOFOLLOW_LINKS) -> Files.copy(path, destination)
          else -> error("unsupported capture entry")
        }
      }
    }
  }

  private fun flatManifest(root: Path): ByteArray {
    val paths = regularFiles(root).filter { it != CHECKSUMS }.sortedWith(::compareUnsignedUtf8)
    return paths.joinToString("\n", postfix = "\n") { path -> "${Sha256.digest(root.resolve(path)).hex}  $path" }.encodeToByteArray()
  }

  private fun recursiveManifest(root: Path): ByteArray =
    regularFiles(root)
      .filter { it != CHECKSUMS }
      .sortedWith(::compareUnsignedUtf8)
      .joinToString("\n", postfix = "\n") { path -> "${Sha256.digest(root.resolve(path)).hex}  $path" }
      .encodeToByteArray()

  private fun regularFiles(root: Path): List<String> =
    Files.walk(root).use { paths ->
      paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
        .map(root::relativize)
        .map(::portablePath)
        .toList()
    }

  private fun completed(computation: ComparisonComputation): ComparisonComputation.Completed =
    computation as? ComparisonComputation.Completed ?: error("comparison rejected")

  private fun CampaignReceiptInput.record(distribution: String): CampaignReceiptRecord =
    CampaignReceiptRecord(
      role.shortName,
      distribution,
      manifestSha256,
      files.map { CampaignFileFact(it.relativePath, it.byteLength, it.sha256) },
      settleDuration.toMillis(),
      sequence,
    )

  private fun HostDocumentRef.refJson(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
    put("path", path)
    put("sha256", sha256.hex)
  }

  private fun PolicyOutcome.wire(): String = name.lowercase().replace('_', '-')
  private fun ObjectNode.objectNode(name: String): ObjectNode = get(name).asObject()
  private fun portablePath(path: Path): String =
    (0 until path.nameCount).joinToString("/") { path.getName(it).toString() }
  private fun compareUnsignedUtf8(left: String, right: String): Int =
    java.util.Arrays.compareUnsigned(left.encodeToByteArray(), right.encodeToByteArray())

  private data class MaterializedCapture(
    val projection: CaptureBundleVerifier.Projection,
    val record: CampaignCaptureRecord,
  )

  private data class OutputHostRefs(
    val preflight: HostDocumentRef,
    val watcher: HostDocumentRef,
    val postflight: HostDocumentRef,
    val restoration: HostDocumentRef,
  )

  private const val CAPTURE_JSON = "capture.json"
  private const val CAMPAIGN_JSON = "campaign.json"
  private const val COMPARISON_JSON = "comparison.json"
  private const val COMPARISON_MD = "comparison.md"
  private const val CHECKSUMS = "checksums.sha256"
  private const val CANDIDATE_COMPARISON = "comparisons/candidate"
}

internal class MaterializedCampaign internal constructor(
  mint: Any,
  val root: Path,
  val campaignSha256: Sha256,
  val manifestSha256: Sha256,
  val exit: RunnerExit,
) {
  init {
    require(mint === MATERIALIZED_CAMPAIGN_MINT) { "campaign materialization must be materializer-minted" }
  }
}

private val MATERIALIZED_CAMPAIGN_MINT = Any()
