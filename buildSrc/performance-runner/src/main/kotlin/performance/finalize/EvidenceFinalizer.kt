/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.finalize

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.Comparator
import performance.compare.CaptureBundleVerifier
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.publication.AtomicPublicationRequest
import performance.publication.AtomicPublisher
import performance.publication.ChecksumManifest
import performance.publication.PublicationCommand
import performance.publication.PublicationOutcome
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind
import tools.jackson.databind.node.ObjectNode

/** Deep finalization boundary: reverify immutable private evidence, then publish it exactly once. */
internal class EvidenceFinalizer private constructor(
  private val command: PublicationCommand = SYSTEM_COMMAND,
  private val checkpoint: FinalizationCheckpoint = FinalizationCheckpoint {},
) {
  fun finalizeDiagnostic(request: DiagnosticFinalizationRequest): FinalizationOutcome {
    if (!validDiagnostic(request)) {
      return finalizeInvalid(
        InvalidFinalizationRequest(
          request.artifactParent,
          request.runToken,
          FinalizationFailure.INPUT_OR_PROTOCOL_INVALID,
        ),
      )
    }
    return publish(request.sourceRoot, request.artifactParent, request.runToken, request.terminal)
  }

  fun finalizeStandaloneComparison(
    request: StandaloneComparisonFinalizationRequest,
  ): FinalizationOutcome {
    if (!validStandaloneComparison(request.sourceRoot)) {
      return finalizeInvalid(
        InvalidFinalizationRequest(
          request.artifactParent,
          request.runToken,
          FinalizationFailure.COMPARISON_INCOMPATIBLE,
        ),
      )
    }
    return publish(request.sourceRoot, request.artifactParent, request.runToken, request.terminal)
  }

  fun finalizeCampaign(request: CampaignFinalizationRequest): FinalizationOutcome {
    if (!validCampaign(request.sourceRoot)) {
      return finalizeInvalid(
        InvalidFinalizationRequest(
          request.artifactParent,
          request.runToken,
          FinalizationFailure.INPUT_OR_PROTOCOL_INVALID,
        ),
      )
    }
    return publish(request.sourceRoot, request.artifactParent, request.runToken, request.terminal)
  }

  fun finalizeFreeze(
    request: FreezeFinalizationRequest,
    verifyDistribution: (Path) -> Boolean,
  ): FinalizationOutcome {
    if (!runCatching { verifyDistribution(request.sourceRoot) }.getOrDefault(false)) {
      return finalizeInvalid(
        InvalidFinalizationRequest(
          request.artifactParent,
          request.runToken,
          FinalizationFailure.INPUT_OR_PROTOCOL_INVALID,
        ),
      )
    }
    return when (
      val outcome =
        AtomicPublisher.publishVerifiedDistribution(
          AtomicPublicationRequest(
            request.sourceRoot,
            request.artifactParent,
            request.runToken,
            request.terminal,
          ),
          verifyDistribution,
          command,
          checkpoint = { transition ->
            checkpoint.reached(FinalizationTransition.valueOf(transition.name))
          },
        )
    ) {
      is PublicationOutcome.Published -> FinalizationOutcome.Published(outcome.target, outcome.exit)
      is PublicationOutcome.Rejected -> FinalizationOutcome.Rejected(outcome.exit)
    }
  }

  fun finalizeInvalid(request: InvalidFinalizationRequest): FinalizationOutcome {
    if (AtomicPublisher.verifyReservation(request.artifactParent, request.runToken) == null) {
      return FinalizationOutcome.Rejected(request.failure.exit)
    }
    val source = Files.createTempDirectory("revoman-invalid-finalization-").toRealPath()
    return try {
      val invalid = Files.createDirectory(source.resolve("INVALID"))
      writeFsynced(invalid.resolve("reason"), "${request.failure.name}\n".encodeToByteArray())
      writeFsynced(
        source.resolve("stderr.log"),
        "performance-runner: ${request.failure.name}\n".encodeToByteArray(),
      )
      ChecksumManifest.write(source)
      publish(
        source,
        request.artifactParent,
        request.runToken,
        request.failure.exit,
        destinationName = "INVALID-${request.runToken}",
      )
    } finally {
      cleanupOwnedTree(source)
    }
  }

  private fun publish(
    source: Path,
    artifactParent: Path,
    runToken: String,
    terminal: performance.runner.RunnerExit,
    destinationName: String = runToken,
  ): FinalizationOutcome =
    when (
      val outcome =
        AtomicPublisher.publish(
          AtomicPublicationRequest(source, artifactParent, runToken, terminal, destinationName),
          command,
          checkpoint = { transition ->
            checkpoint.reached(FinalizationTransition.valueOf(transition.name))
          },
        )
    ) {
      is PublicationOutcome.Published -> FinalizationOutcome.Published(outcome.target, outcome.exit)
      is PublicationOutcome.Rejected -> FinalizationOutcome.Rejected(outcome.exit)
    }

  private fun validDiagnostic(request: DiagnosticFinalizationRequest): Boolean = runCatching {
    val verification = CaptureBundleVerifier.verify(request.sourceRoot)
    require(verification.failures.isEmpty())
    val projection = requireNotNull(verification.projection)
    require(projection.outcomeStatus == "valid")
    require(projection.outcomeStrength in setOf("diagnostic", "canary"))
    require(projection.profilerSummaryPresent == (request.profiler != null))
    request.profiler?.let { require(validProfiler(request.sourceRoot, it)) }
    true
  }.getOrDefault(false)

  private fun validProfiler(sourceRoot: Path, evidence: ProfilerFinalizationEvidence): Boolean =
    runCatching {
      val operation = evidence.operationRoot
      require(operation.isAbsolute && operation == operation.toAbsolutePath().normalize())
      require(Files.isDirectory(operation, NOFOLLOW_LINKS) && !Files.isSymbolicLink(operation))
      require(evidence.intentPath.parent == operation && evidence.completionPath.parent == operation)
      Files.walk(operation).use { paths ->
        paths.forEach { path ->
          require(!Files.isSymbolicLink(path))
          require(!path.fileName.toString().endsWith(".jfr"))
          require(Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isRegularFile(path, NOFOLLOW_LINKS))
        }
      }
      val summaryPath = sourceRoot.resolve(PROFILER_SUMMARY)
      val summaryBytes = Files.readAllBytes(summaryPath)
      require(EvidenceSchemaValidator().validate(SchemaKind.PROFILER_SUMMARY, summaryBytes).isEmpty())
      val summary = canonicalObject(summaryBytes)
      val capture = canonicalObject(Files.readAllBytes(sourceRoot.resolve(CAPTURE_JSON)))
      val reference = capture.get("profilerSummary").asObject()
      val captureId = capture.get("identity").get("captureId").asString()
      val rawHash = summary.get("rawInputSha256").asString()
      val summaryHash = Sha256.digest(summaryBytes).hex
      require(summary.get("captureId").asString() == captureId)
      require(reference.get("path").asString() == PROFILER_SUMMARY)
      require(reference.get("sha256").asString() == summaryHash)
      require(reference.get("rawInputSha256").asString() == rawHash)
      require(
        reference.get("variantSha256").asString() ==
          capture.get("profile").get("variantSha256").asString(),
      )
      val intentBytes = Files.readAllBytes(evidence.intentPath)
      val intent = canonicalObject(intentBytes)
      require(intent.size() == 5)
      require(intent.get("schemaVersion").asString() == "profiler-scrub-intent-v1")
      require(intent.get("captureId").asString() == captureId)
      require(intent.get("provisionalCaptureSha256").asString() == evidence.provisionalCaptureSha256.hex)
      require(intent.get("rawInputSha256").asString() == rawHash)
      require(intent.get("summarySha256").asString() == summaryHash)
      val completion = canonicalObject(Files.readAllBytes(evidence.completionPath))
      require(completion.size() == 3)
      require(completion.get("schemaVersion").asString() == "profiler-scrub-complete-v1")
      require(completion.get("intentSha256").asString() == Sha256.digest(intentBytes).hex)
      require(completion.get("summarySha256").asString() == summaryHash)
      true
    }.getOrDefault(false)

  private fun validCampaign(root: Path): Boolean = runCatching {
    require(ChecksumManifest.verify(root))
    val bytes = Files.readAllBytes(root.resolve(CAMPAIGN_JSON))
    canonicalObject(bytes)
    require(EvidenceSchemaValidator().validate(SchemaKind.CAMPAIGN, bytes).isEmpty())
    true
  }.getOrDefault(false)

  private fun validStandaloneComparison(root: Path): Boolean = runCatching {
    require(ChecksumManifest.verify(root))
    val files = Files.list(root).use { it.map { path -> path.fileName.toString() }.toList().toSet() }
    require(files == setOf(COMPARISON_JSON, COMPARISON_MD, CHECKSUMS))
    val bytes = Files.readAllBytes(root.resolve(COMPARISON_JSON))
    canonicalObject(bytes)
    require(EvidenceSchemaValidator().validate(SchemaKind.COMPARISON, bytes).isEmpty())
    require(CanonicalJson.parseStrict(bytes).get("strength").asString() == "diagnostic")
    true
  }.getOrDefault(false)

  private fun canonicalObject(bytes: ByteArray): ObjectNode =
    CanonicalJson.parseStrict(bytes).asObject().also { document ->
      require(CanonicalJson.encode(document).contentEquals(bytes))
    }

  private fun writeFsynced(path: Path, bytes: ByteArray) {
    FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
      val buffer = ByteBuffer.wrap(bytes)
      while (buffer.hasRemaining()) channel.write(buffer)
      channel.force(true)
    }
  }

  private fun cleanupOwnedTree(root: Path) {
    if (!Files.exists(root, NOFOLLOW_LINKS)) return
    Files.walk(root).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }

  companion object {
    @JvmSynthetic internal fun system(): EvidenceFinalizer = EvidenceFinalizer()

    @JvmSynthetic
    internal fun forTest(
      command: PublicationCommand,
      checkpoint: FinalizationCheckpoint = FinalizationCheckpoint {},
    ): EvidenceFinalizer = EvidenceFinalizer(command, checkpoint)

    private val SYSTEM_COMMAND = PublicationCommand { arguments ->
      ProcessBuilder(arguments).inheritIO().start().waitFor()
    }
    const val CAPTURE_JSON = "capture.json"
    const val PROFILER_SUMMARY = "profiler-summary.json"
    const val CAMPAIGN_JSON = "campaign.json"
    const val COMPARISON_JSON = "comparison.json"
    const val COMPARISON_MD = "comparison.md"
    const val CHECKSUMS = "checksums.sha256"
  }
}
