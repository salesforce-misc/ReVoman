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
import java.time.Duration
import java.time.Instant
import performance.campaign.CaptureRole
import performance.campaign.ReceiptFileFact
import performance.compare.CaptureBundleVerifier
import performance.compare.CaptureComparator
import performance.compare.ComparisonComputation
import performance.compare.ComparisonKind
import performance.compare.ComparisonRequest
import performance.distribution.DistributionLayout
import performance.distribution.DistributionValidation
import performance.distribution.DistributionValidationRequest
import performance.distribution.DistributionValidator
import performance.distribution.JavaRuntimeIdentity
import performance.distribution.VerifiedDistribution
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.HostDocumentRef
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

internal class VerifiedCampaignInput internal constructor(
  mint: Any,
  val request: CampaignComputationRequest,
  val attempts: List<VerifiedAttempt>,
  val baselineDistribution: VerifiedDistribution,
  val candidateDistribution: VerifiedDistribution,
  val hostDocuments: ValidatedHostDocuments,
) {
  init {
    require(mint === VALIDATION_MINT) { "campaign input must be validator-minted" }
  }
}

internal class VerifiedAttempt internal constructor(
  mint: Any,
  val input: CampaignAttemptInput,
  val a1: CaptureBundleVerifier.Projection,
  val a2: CaptureBundleVerifier.Projection,
  val b: CaptureBundleVerifier.Projection?,
  val calibration: ComparisonComputation.Completed?,
) {
  init {
    require(mint === VALIDATION_MINT) { "campaign attempt must be validator-minted" }
  }

  val projections: List<CaptureBundleVerifier.Projection>
    get() = listOfNotNull(a1, a2, b)
}

internal class ValidatedHostDocuments internal constructor(
  mint: Any,
  val preflight: ByteArray,
  val watcher: ByteArray,
  val postflight: ByteArray,
  val restoration: ByteArray,
) {
  init {
    require(mint === VALIDATION_MINT) { "host documents must be validator-minted" }
  }
}

private val VALIDATION_MINT = Any()

internal object CampaignValidator {
  fun verifyCapturePaths(request: CampaignComputationRequest): List<CaptureBundleVerifier.Verification> =
    request.attempts.flatMap { attempt ->
      buildList {
        add(CaptureBundleVerifier.verify(attempt.a1.sealedRoot))
        add(CaptureBundleVerifier.verify(attempt.a2.sealedRoot))
        attempt.b?.let { add(CaptureBundleVerifier.verify(it.sealedRoot)) }
      }
    }

  fun validate(
    request: CampaignComputationRequest,
    verifications: List<CaptureBundleVerifier.Verification>,
    comparator: CaptureComparator,
  ): VerifiedCampaignInput? =
    runCatching {
        require(verifications.all { it.failures.isEmpty() && it.projection != null })
        require(safeId(request.campaignId) && safeId(request.performanceSessionId))
        require(request.attempts.isNotEmpty() && request.attempts.size <= FORKS.size)
        require(request.attempts.map(CampaignAttemptInput::forks) == FORKS.take(request.attempts.size))
        require(request.attempts.map(CampaignAttemptInput::attemptId).all(::safeId))
        require(request.attempts.map(CampaignAttemptInput::attemptId).distinct().size == request.attempts.size)

        val projections = verifications.map { requireNotNull(it.projection) }
        require(projections.all(::validIngressEvidence))
        val roots = projections.map { it.root.toRealPath() }
        require(roots.distinct().size == roots.size && roots.noneAliased())
        val captureIds = projections.map { it.identity.captureId }
        val processIds = projections.map { it.identity.processRunId }
        require(captureIds.all(::safeId) && captureIds.distinct().size == captureIds.size)
        require(processIds.all(::safeId) && processIds.distinct().size == processIds.size)
        require(projections.all { it.identity.performanceSessionId == request.performanceSessionId })
        require(projections.all(::validCapture))
        require(projections.map { it.runtime.hostIdentity }.distinct().size == 1)
        require(projections.map { it.protocol }.distinct().size == 1)
        require(projections.map { it.logging }.distinct().size == 1)
        require(projections.map { it.benchmarkProtocolVersion }.distinct().size == 1)

        val ordered = request.attempts.zipProjections(projections)
        val sequences = ordered.flatMap { it.projections }.map { it.identity.sessionSequence }
        require(sequences == (sequences.first() until sequences.first() + sequences.size).toList())
        ordered.flatMap { it.projections }.zipWithNext().forEach { (left, right) ->
          require(!right.startedAt.isBefore(left.completedAt))
        }
        val duration = Duration.between(ordered.first().a1.startedAt, ordered.last().projections.last().completedAt)
        require(!duration.isNegative && duration <= MAX_CAMPAIGN_DURATION)

        val baseline = validateDistribution(request.baselineDistribution)
        val candidate = validateDistribution(request.candidateDistribution)
        require(baseline.root != candidate.root)
        require(listOf(baseline.root, candidate.root).noneAliased())
        require(baseline.metadata.protocol == candidate.metadata.protocol)
        require(baseline.metadata.classpath.runnerClasspath == candidate.metadata.classpath.runnerClasspath)

        val recalculated = ordered.map { attempt ->
          require(attempt.a1.profile.forks == attempt.input.forks)
          require(attempt.a2.profile.forks == attempt.input.forks)
          require(attempt.a1.profile.family == "warm" && attempt.a2.profile.family == "warm")
          require(attempt.a1.identity.sessionSequence + 1 == attempt.a2.identity.sessionSequence)
          validateCaptureInput(attempt.input.a1, CaptureRole.BASELINE_A1, attempt.a1, baseline)
          validateCaptureInput(attempt.input.a2, CaptureRole.BASELINE_A2, attempt.a2, baseline)
          val comparison =
            comparator.compare(
              ComparisonRequest(
                runnerDistribution = baseline.root,
                kind = ComparisonKind.CALIBRATION,
                baseline = attempt.a1.root,
                candidate = attempt.a2.root,
              ),
            ) as? ComparisonComputation.Completed
          require(comparison != null)
          val passed = requireNotNull(comparison.document.calibration).passed
          require(passed == attempt.input.calibrationPassed)
          if (passed) {
            val b = requireNotNull(attempt.b)
            val bInput = requireNotNull(attempt.input.b)
            require(b.profile.forks == attempt.input.forks && b.profile.family == "warm")
            require(attempt.a2.identity.sessionSequence + 1 == b.identity.sessionSequence)
            validateCaptureInput(bInput, CaptureRole.CANDIDATE_B, b, candidate)
            validateCandidateDistribution(b, candidate)
          } else {
            require(attempt.b == null && attempt.input.b == null)
          }
          VerifiedAttempt(
            VALIDATION_MINT,
            attempt.input,
            attempt.a1,
            attempt.a2,
            attempt.b,
            comparison,
          )
        }
        val passing = recalculated.filter { it.calibration?.document?.calibration?.passed == true }
        if (passing.isEmpty()) {
          require(recalculated.size == FORKS.size)
          require(request.selectedAttemptId == null)
        } else {
          require(passing.size == 1 && passing.single() === recalculated.last())
          require(recalculated.dropLast(1).all { it.calibration?.document?.calibration?.passed == false })
          require(request.selectedAttemptId == passing.single().input.attemptId)
        }
        val qualification = validateHostDocuments(request, projections)
        require(request.outputRoot.safeAbsentRoot())
        require((roots + listOf(baseline.root, candidate.root, request.qualificationRoot.toRealPath())).none { path ->
          path.startsWith(request.outputRoot) || request.outputRoot.startsWith(path)
        })
        VerifiedCampaignInput(
          VALIDATION_MINT,
          request,
          recalculated,
          baseline,
          candidate,
          qualification,
        )
      }
      .getOrNull()

  private fun List<CampaignAttemptInput>.zipProjections(
    projections: List<CaptureBundleVerifier.Projection>,
  ): List<VerifiedAttempt> {
    var index = 0
    return map { attempt ->
      val a1 = projections[index++]
      val a2 = projections[index++]
      val b = if (attempt.b == null) null else projections[index++]
      require(a1.root == attempt.a1.sealedRoot.toAbsolutePath().normalize())
      require(a2.root == attempt.a2.sealedRoot.toAbsolutePath().normalize())
      require(b?.root == attempt.b?.sealedRoot?.toAbsolutePath()?.normalize())
      VerifiedAttempt(VALIDATION_MINT, attempt, a1, a2, b, null)
    }.also { require(index == projections.size) }
  }

  private fun validIngressEvidence(capture: CaptureBundleVerifier.Projection): Boolean =
    capture.outcomeStrength == "diagnostic" &&
      capture.qualificationKind == "controlledMacBoundedDiagnostic"

  private fun validCapture(capture: CaptureBundleVerifier.Projection): Boolean =
    capture.outcomeStatus == "valid" &&
      capture.processExit == 0 &&
      !capture.completedAt.isBefore(capture.startedAt) &&
      capture.profile.profiler == "none" &&
      !capture.profilerSummaryPresent &&
      listOf(
          capture.provenance.treatment,
          capture.provenance.immutableHarness,
          capture.provenance.distributionFreezer,
          capture.provenance.captureRunner,
        )
        .all { it.treeClean }

  private fun validateCaptureInput(
    input: CampaignCaptureInput,
    role: CaptureRole,
    projection: CaptureBundleVerifier.Projection,
    distribution: VerifiedDistribution,
  ) {
    require(input.role == role)
    require(input.sealedRoot.toAbsolutePath().normalize() == projection.root)
    require(input.receipt.role == role)
    require(input.receipt.sequence == projection.identity.sessionSequence.toLong())
    require(input.receipt.settleDuration == REQUIRED_SETTLE)
    require(input.receipt == snapshotReceipt(role, projection.identity.sessionSequence.toLong(), distribution.root))
  }

  private fun validateCandidateDistribution(
    capture: CaptureBundleVerifier.Projection,
    distribution: VerifiedDistribution,
  ) {
    val classpath = distribution.metadata.classpath.benchmarkClasspath
    val runnerClasspath = distribution.metadata.classpath.runnerClasspath
    val production = classpath.single { it.path == DistributionLayout.PRODUCTION_JAR }
    val benchmark = classpath.single { it.path == DistributionLayout.BENCHMARK_JAR }
    require(capture.artifacts.production.path == production.path)
    require(capture.artifacts.production.sha256 == production.sha256)
    require(capture.artifacts.benchmark.path == benchmark.path)
    require(capture.artifacts.benchmark.sha256 == benchmark.sha256)
    require(capture.artifacts.orderedClasspath.map { it.path to it.sha256 } == classpath.map { it.path to it.sha256 })
    require(capture.artifacts.executingRunner.path == runnerClasspath.first().path)
    require(capture.artifacts.executingRunner.sha256 == runnerClasspath.first().sha256)
    require(capture.artifacts.orderedRunnerClasspath.map { it.path to it.sha256 } == runnerClasspath.map { it.path to it.sha256 })
    require(capture.artifacts.distribution.path == DistributionLayout.CHECKSUM_MANIFEST)
    require(capture.artifacts.distribution.sha256 == Sha256.digest(distribution.root.resolve(DistributionLayout.CHECKSUM_MANIFEST)))
  }

  private fun validateDistribution(root: Path): VerifiedDistribution {
    val executable =
      Path.of(checkNotNull(ProcessHandle.current().info().command().orElse(null)))
        .toAbsolutePath()
        .normalize()
    val validation =
      DistributionValidator().validate(
        DistributionValidationRequest(
          root,
          JavaRuntimeIdentity(executable, Runtime.version().feature(), Sha256.digest(executable)),
        ),
      )
    return (validation as DistributionValidation.Valid).distribution
  }

  private fun validateHostDocuments(
    request: CampaignComputationRequest,
    captures: List<CaptureBundleVerifier.Projection>,
  ): ValidatedHostDocuments {
    require(request.qualificationRoot.safeExistingRoot())
    val evidence = request.qualification
    require(evidence.cleanupPassed)
    require(evidence.policyHash == captures.first().protocol.qualificationPolicySha256)
    require(captures.all { it.qualificationPolicySha256 == evidence.policyHash })
    val refs = listOf(evidence.preflight, evidence.watcher, evidence.postflight, evidence.restoration)
    require(refs.map(HostDocumentRef::path).distinct().size == refs.size)
    val documents = refs.mapIndexed { index, ref -> readHostDocument(request.qualificationRoot, ref, HOST_SCHEMAS[index]) }
    val preflight = documents[0].asObject()
    val watcher = documents[1].asObject()
    val postflight = documents[2].asObject()
    val restoration = documents[3].asObject()
    listOf(preflight, watcher, postflight, restoration).forEach { document ->
      require(document.text("policySha256") == evidence.policyHash.hex)
    }
    require(preflight.text("operationId") == request.campaignId)
    require(preflight.text("adapterSha256") == captures.first().protocol.hostAdapterSha256.hex)
    require(preflight.text("architecture") == captures.first().runtime.frozen.architecture)
    require(preflight.get("lockAcquired").asBoolean() && preflight.objectNode("checks").allPass())
    require(watcher.text("terminalState") == "completed")
    require(watcher.get("expectedSamples").asInt() == watcher.get("observedSamples").asInt())
    require(watcher.get("observedSamples").asInt() == watcher.arrayNode("observations").size())
    require(watcher.arrayNode("observations").all { it.asObject().validObservation() })
    require(postflight.get("processExit").asInt() == 0 && postflight.objectNode("checks").allPass())
    require(restoration.get("cleanupPassed").asBoolean())
    require(restoration.get("lockReleaseReady").asBoolean())
    require(restoration.text("restoredState") == "passed")
    val firstStart = captures.minOf { it.startedAt }
    val lastComplete = captures.maxOf { it.completedAt }
    require(!Instant.parse(preflight.text("observedAtUtc")).isAfter(firstStart))
    require(!Instant.parse(watcher.text("startedAtUtc")).isAfter(firstStart))
    require(!Instant.parse(watcher.text("completedAtUtc")).isBefore(lastComplete))
    require(!Instant.parse(postflight.text("observedAtUtc")).isBefore(lastComplete))
    require(!Instant.parse(restoration.text("observedAtUtc")).isBefore(Instant.parse(postflight.text("observedAtUtc"))))
    val watcherStart = Instant.parse(watcher.text("startedAtUtc"))
    val watcherComplete = Instant.parse(watcher.text("completedAtUtc"))
    val observationTimes =
      watcher.arrayNode("observations").values().asSequence().map {
        Instant.parse(it.asObject().text("observedAtUtc"))
      }.toList()
    require(observationTimes == observationTimes.sorted())
    require(observationTimes.all { !it.isBefore(watcherStart) && !it.isAfter(watcherComplete) })
    val preSnapshot = preflight.objectNode("snapshot")
    val postSnapshot = postflight.objectNode("snapshot")
    listOf("containerFingerprintSha256", "runtimeFingerprintSha256").forEach { name ->
      val expected = preSnapshot.text(name)
      require(postSnapshot.text(name) == expected)
      require(watcher.arrayNode("observations").all { it.asObject().text(name) == expected })
    }
    return ValidatedHostDocuments(
      VALIDATION_MINT,
      CanonicalJson.encode(preflight),
      CanonicalJson.encode(watcher),
      CanonicalJson.encode(postflight),
      CanonicalJson.encode(restoration),
    )
  }

  private fun readHostDocument(rootInput: Path, ref: HostDocumentRef, schema: SchemaKind): JsonNode {
    require(relativePath(ref.path))
    val root = rootInput.toRealPath()
    val path = root.resolve(ref.path).normalize()
    require(path.startsWith(root) && Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
    val bytes = Files.readAllBytes(path)
    require(Sha256.digest(bytes) == ref.sha256)
    require(EvidenceSchemaValidator().validate(schema, bytes).isEmpty())
    return CanonicalJson.parseStrict(bytes)
  }

  private fun snapshotReceipt(role: CaptureRole, sequence: Long, rootInput: Path): CampaignReceiptInput {
    val root = rootInput.toRealPath()
    val objects = Files.walk(root).use { it.toList() }
    require(objects.all { path ->
      path == root ||
        (!Files.isSymbolicLink(path) &&
          (Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isRegularFile(path, NOFOLLOW_LINKS)))
    })
    val files =
      objects.asSequence()
        .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
        .map(root::relativize)
        .map(::portablePath)
        .sortedWith(::compareUnsignedUtf8)
        .map { relative ->
          val path = root.resolve(relative)
          ReceiptFileFact(relative, Files.size(path), Sha256.digest(path))
        }
        .toList()
    return CampaignReceiptInput(
      role,
      root,
      Sha256.digest(root.resolve(DistributionLayout.CHECKSUM_MANIFEST)),
      files,
      REQUIRED_SETTLE,
      sequence,
    )
  }

  private fun ObjectNode.allPass(): Boolean = properties().all { it.value.asString() == "pass" }

  private fun ObjectNode.validObservation(): Boolean =
    text("event") == "none" &&
      text("memoryPressure") == "normal" &&
      text("powerState") == "ac" &&
      text("thermalState") == "nominal"

  private fun Path.safeAbsentRoot(): Boolean =
    isAbsolute &&
      this == toAbsolutePath().normalize() &&
      fileName != null &&
      SAFE_ID.matches(fileName.toString()) &&
      !Files.exists(this, NOFOLLOW_LINKS) &&
      parent?.let { Files.isDirectory(it, NOFOLLOW_LINKS) && !hasSymbolicLinkComponent(it) } == true

  private fun Path.safeExistingRoot(): Boolean =
    isAbsolute &&
      this == toAbsolutePath().normalize() &&
      Files.isDirectory(this, NOFOLLOW_LINKS) &&
      !Files.isSymbolicLink(this) &&
      !hasSymbolicLinkComponent(this)

  private fun List<Path>.noneAliased(): Boolean =
    indices.all { left ->
      ((left + 1) until size).all { right ->
        !this[left].startsWith(this[right]) && !this[right].startsWith(this[left])
      }
    }

  private fun hasSymbolicLinkComponent(path: Path): Boolean {
    var current = path.root ?: return true
    return path.any { component ->
      current = current.resolve(component)
      Files.isSymbolicLink(current)
    }
  }

  private fun relativePath(value: String): Boolean {
    val path = runCatching { Path.of(value) }.getOrNull() ?: return false
    return value.isNotBlank() && !path.isAbsolute && path.normalize() == path &&
      path.none { it.toString() == ".." } && '\\' !in value
  }

  private fun safeId(value: String): Boolean =
    SAFE_ID.matches(value) &&
      !IP_ADDRESS.matches(value) &&
      !SENSITIVE_ID.containsMatchIn(value) &&
      !CREDENTIAL_ID.containsMatchIn(value)

  private fun portablePath(path: Path): String =
    (0 until path.nameCount).joinToString("/") { path.getName(it).toString() }

  private fun compareUnsignedUtf8(left: String, right: String): Int =
    java.util.Arrays.compareUnsigned(left.encodeToByteArray(), right.encodeToByteArray())

  private fun ObjectNode.text(name: String): String = get(name).asString()
  private fun ObjectNode.objectNode(name: String): ObjectNode = get(name).asObject()
  private fun ObjectNode.arrayNode(name: String) = get(name).asArray()

  private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
  private val IP_ADDRESS = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
  private val SENSITIVE_ID = Regex("secret|password|api[_-]?key|bearer", RegexOption.IGNORE_CASE)
  private val CREDENTIAL_ID = Regex("gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|(?:AKIA|ASIA)[0-9A-Z]{16}")
  private val FORKS = listOf(10, 20, 40)
  private val REQUIRED_SETTLE = Duration.ofSeconds(10)
  private val MAX_CAMPAIGN_DURATION = Duration.ofHours(2)
  private val HOST_SCHEMAS = listOf(SchemaKind.PREFLIGHT, SchemaKind.WATCHER, SchemaKind.POSTFLIGHT, SchemaKind.RESTORATION)

}
