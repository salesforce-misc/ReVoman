/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.finalize

import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.*
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** The sole canonical encoding boundary for private capture state and sealed capture documents. */
internal object CaptureDocumentCodec {
  fun encode(document: ProvisionalCaptureDocument): ByteArray {
    val root =
      common(document).apply {
        set(
          "outcome",
          objectNode {
            put("completedAtUtc", document.outcome.completedAtUtc)
            put("processExit", document.outcome.processExit)
            set("reasons", stringArrayNode(document.outcome.reasons.map(::reason)))
            put("startedAtUtc", document.outcome.startedAtUtc)
            put("status", document.outcome.status.name.lowercase())
            put("strength", document.outcome.strength.name.lowercase())
          },
        )
        document.rawProfilerInputSha256?.let { put("rawProfilerInputSha256", it.hex) }
      }
    val bytes = CanonicalJson.encode(root)
    val failures = EvidenceSchemaValidator().validate(SchemaKind.CAPTURE_PROVISIONAL, bytes)
    require(failures.isEmpty()) { failures.joinToString() }
    return bytes
  }

  fun decode(bytes: ByteArray): ProvisionalCaptureDocument {
    require(EvidenceSchemaValidator().validate(SchemaKind.CAPTURE_PROVISIONAL, bytes).isEmpty())
    val root = CanonicalJson.parseStrict(bytes).asObject()
    require(CanonicalJson.encode(root).contentEquals(bytes))
    val outcome = root.objectNode("outcome")
    return ProvisionalCaptureDocument(
      schemaVersion = root.text("schemaVersion"),
      benchmarkProtocolVersion = root.text("benchmarkProtocolVersion"),
      identity = root.objectNode("identity").identity(),
      outcome =
        ProvisionalCaptureOutcome(
          status = EvidenceStatus.valueOf(outcome.text("status").uppercase()),
          strength = ProvisionalEvidenceStrength.valueOf(outcome.text("strength").uppercase()),
          reasons =
            outcome.arrayNode("reasons").values().asSequence().map { value ->
              reason(value.asString())
            }.toList(),
          startedAtUtc = outcome.text("startedAtUtc"),
          completedAtUtc = outcome.text("completedAtUtc"),
          processExit = outcome.int("processExit"),
        ),
      provenance = root.objectNode("provenance").provenance(),
      protocol = root.objectNode("protocol").protocol(),
      artifacts = root.objectNode("artifacts").artifacts(),
      toolchain = root.objectNode("toolchain").toolchain(),
      runtime = root.objectNode("runtime").runtime(),
      logging = root.objectNode("logging").logging(),
      profile = root.objectNode("profile").profile(),
      cells = root.arrayNode("cells").values().asSequence().map { it.asObject().cell() }.toList(),
      rawProfilerInputSha256 = root.get("rawProfilerInputSha256")?.let { Sha256.parse(it.asString()) },
    )
  }

  fun render(document: CaptureDocument): ObjectNode =
    common(document).apply {
      set(
        "outcome",
        objectNode {
          set("claimEligibilityReasons", stringArrayNode(document.outcome.claimEligibilityReasons.map(::reason)))
          put("completedAtUtc", document.outcome.completedAtUtc)
          put("processExit", document.outcome.processExit)
          put("startedAtUtc", document.outcome.startedAtUtc)
          put("status", document.outcome.status.name.lowercase())
          put("strength", document.outcome.strength.name.lowercase())
        },
      )
      set("qualification", render(document.qualification))
      document.profilerSummary?.let { summary ->
        set(
          "profilerSummary",
          objectNode {
            put("path", summary.path)
            put("rawInputSha256", summary.rawInputSha256.hex)
            put("sha256", summary.sha256.hex)
            put("variantSha256", summary.variantSha256.hex)
          },
        )
      }
    }

  private fun common(document: ProvisionalCaptureDocument): ObjectNode =
    common(
      schemaVersion = document.schemaVersion,
      benchmarkProtocolVersion = document.benchmarkProtocolVersion,
      identity = document.identity,
      provenance = document.provenance,
      protocol = document.protocol,
      artifacts = document.artifacts,
      toolchain = document.toolchain,
      runtime = document.runtime,
      logging = document.logging,
      profile = document.profile,
      cells = document.cells,
    )

  private fun common(document: CaptureDocument): ObjectNode =
    common(
      schemaVersion = document.schemaVersion,
      benchmarkProtocolVersion = document.benchmarkProtocolVersion,
      identity = document.identity,
      provenance = document.provenance,
      protocol = document.protocol,
      artifacts = document.artifacts,
      toolchain = document.toolchain,
      runtime = document.runtime,
      logging = document.logging,
      profile = document.profile,
      cells = document.cells,
    )

  private fun common(
    schemaVersion: String,
    benchmarkProtocolVersion: String,
    identity: CaptureIdentity,
    provenance: ProvenanceRoles,
    protocol: ProtocolIdentity,
    artifacts: CaptureArtifacts,
    toolchain: ToolchainIdentity,
    runtime: RuntimeIdentity,
    logging: LoggingProfileIdentity,
    profile: CaptureProfileIdentity,
    cells: List<CaptureCell>,
  ): ObjectNode =
    objectNode {
      set("artifacts", render(artifacts))
      put("benchmarkProtocolVersion", benchmarkProtocolVersion)
      set("cells", arrayNode(cells, ::render))
      set("identity", render(identity))
      set("logging", render(logging))
      set("profile", render(profile))
      set("protocol", render(protocol))
      set("provenance", render(provenance))
      set("runtime", render(runtime))
      put("schemaVersion", schemaVersion)
      set("toolchain", render(toolchain))
    }

  private fun render(value: CaptureIdentity): ObjectNode = objectNode {
    put("captureId", value.captureId)
    put("performanceSessionId", value.performanceSessionId)
    put("processRunId", value.processRunId)
    put("sessionSequence", value.sessionSequence)
  }

  private fun render(value: CaptureArtifacts): ObjectNode = objectNode {
    set("benchmark", render(value.benchmark))
    set("dependencies", arrayNode(value.dependencies, ::render))
    set("distribution", render(value.distribution))
    set("executingRunner", render(value.executingRunner))
    set("orderedClasspath", arrayNode(value.orderedClasspath, ::render))
    set("orderedRunnerClasspath", arrayNode(value.orderedRunnerClasspath, ::render))
    set("production", render(value.production))
    put("rawJmhInputSha256", value.rawJmhInputSha256.hex)
  }

  private fun render(value: ArtifactIdentity): ObjectNode =
    objectNode { put("path", value.path); put("sha256", value.sha256.hex) }

  private fun render(value: DependencyIdentity): ObjectNode =
    objectNode { put("coordinate", value.coordinate); put("sha256", value.sha256.hex) }

  private fun render(value: CaptureCell): ObjectNode = objectNode {
    put("batchSize", value.batchSize)
    put("benchmark", value.benchmark)
    set("derivedForkSummaries", arrayNode(value.derivedForkSummaries, ::render))
    set("jmhResultRow", render(value.jmhResultRow))
    put("mode", value.mode)
    set("parameters", objectNode { value.parameters.forEach(::put) })
    set("primaryMetric", render(value.primaryMetric))
    set("sampleDimensions", render(value.sampleDimensions))
    put("threads", value.threads)
    put("unit", value.unit)
  }

  private fun render(value: ForkSummary): ObjectNode =
    objectNode { put("fork", value.fork); put("sampleCount", value.sampleCount); put("score", value.score) }

  private fun render(value: JmhResultRowRef): ObjectNode =
    objectNode { put("jsonPointer", value.jsonPointer); put("sha256", value.sha256.hex) }

  private fun render(value: PrimaryMetricIdentity): ObjectNode =
    objectNode { put("direction", value.direction); put("name", value.name) }

  private fun render(value: SampleDimensions): ObjectNode = objectNode {
    put("forks", value.forks)
    put("measurementIterations", value.measurementIterations)
    put("samplesPerFork", value.samplesPerFork)
  }

  private fun render(value: LoggingProfileIdentity): ObjectNode =
    objectNode { put("configurationSha256", value.configurationSha256.hex); put("profile", value.profile) }

  private fun render(value: CaptureProfileIdentity): ObjectNode = objectNode {
    put("family", value.family)
    put("forks", value.forks)
    put("identity", value.identity)
    put("measurementIterations", value.measurementIterations)
    put("profiler", value.profiler)
    put("variantSha256", value.variantSha256.hex)
    put("warmupIterations", value.warmupIterations)
  }

  private fun render(value: ProtocolIdentity): ObjectNode = objectNode {
    put("benchmarkProtocolSha256", value.benchmarkProtocolSha256.hex)
    put("benchmarkSourceSha256", value.benchmarkSourceSha256.hex)
    put("comparatorSha256", value.comparatorSha256.hex)
    put("hostAdapterSha256", value.hostAdapterSha256.hex)
    put("qualificationPolicySha256", value.qualificationPolicySha256.hex)
    put("rendererSha256", value.rendererSha256.hex)
    put("schemaSha256", value.schemaSha256.hex)
    put("workloadTreeSha256", value.workloadTreeSha256.hex)
  }

  private fun render(value: ProvenanceRoles): ObjectNode = objectNode {
    set("captureRunner", render(value.captureRunner))
    set("distributionFreezer", render(value.distributionFreezer))
    set("immutableHarness", render(value.immutableHarness))
    set("treatment", render(value.treatment))
  }

  private fun render(value: GitProvenance): ObjectNode =
    objectNode { put("gitSha", value.gitSha); put("treeClean", value.treeClean) }

  private fun render(value: QualificationEvidence): ObjectNode =
    when (value) {
      is QualificationEvidence.ControlledMacBoundedDiagnostic -> objectNode {
        put("campaignFieldsInapplicableReason", value.campaignFieldsInapplicableReason)
        put("kind", "controlledMacBoundedDiagnostic")
        put("policyHash", value.policyHash.hex)
        set("postflight", render(value.postflight))
        set("preflight", render(value.preflight))
        set("restoration", render(value.restoration))
        set("watcher", render(value.watcher))
      }
      is QualificationEvidence.ControlledMacCampaign -> objectNode {
        put("cleanupPassed", value.cleanupPassed)
        put("kind", "controlledMacCampaign")
        put("policyHash", value.policyHash.hex)
        set("postflight", render(value.postflight))
        set("preflight", render(value.preflight))
        set("restoration", render(value.restoration))
        set("watcher", render(value.watcher))
      }
      is QualificationEvidence.GithubHosted -> objectNode {
        set("cleanup", render(value.cleanup))
        put("kind", "githubHosted")
        put("macFieldsInapplicableReason", value.macFieldsInapplicableReason)
        put("policyHash", value.policyHash.hex)
        set("setup", render(value.setup))
      }
    }

  private fun render(value: HostDocumentRef): ObjectNode =
    objectNode { put("path", value.path); put("sha256", value.sha256.hex) }

  private fun render(value: RuntimeIdentity): ObjectNode = objectNode {
    set("environment", objectNode { value.environment.forEach(::put) })
    put("hostId", value.hostId)
    set("jdk", render(value.jdk))
    set("limits", render(value.limits))
    set("linux", render(value.linux))
    set("network", render(value.network))
    set("oci", render(value.oci))
    set("security", render(value.security))
    set("storage", render(value.storage))
    set("substrate", render(value.substrate))
  }

  private fun render(value: JdkIdentity): ObjectNode = objectNode {
    put("binarySha256", value.binarySha256.hex)
    set("jvmArguments", stringArrayNode(value.jvmArguments))
    put("vendor", value.vendor)
    put("version", value.version)
  }

  private fun render(value: RuntimeLimits): ObjectNode = objectNode {
    put("cpuSet", value.cpuSet)
    put("memoryBytes", value.memoryBytes)
    put("memorySwapBytes", value.memorySwapBytes)
    put("pidLimit", value.pidLimit)
  }

  private fun render(value: LinuxIdentity): ObjectNode =
    objectNode { put("architecture", value.architecture); put("kernel", value.kernel); put("os", value.os) }

  private fun render(value: NetworkIdentity): ObjectNode =
    objectNode { put("mode", value.mode); put("pullPolicy", value.pullPolicy) }

  private fun render(value: OciIdentity): ObjectNode = objectNode {
    put("configDigest", value.configDigest)
    put("imageReference", value.imageReference)
    put("platformManifestDigest", value.platformManifestDigest)
  }

  private fun render(value: SecurityIdentity): ObjectNode = objectNode {
    set("capabilities", stringArrayNode(value.capabilities))
    put("noNewPrivileges", value.noNewPrivileges)
    put("readOnlyRoot", value.readOnlyRoot)
    put("user", value.user)
  }

  private fun render(value: StorageIdentity): ObjectNode = objectNode {
    put("distributionSource", value.distributionSource)
    set("writableMounts", stringArrayNode(value.writableMounts))
  }

  private fun render(value: SubstrateIdentity): ObjectNode =
    when (value) {
      is SubstrateIdentity.ControlledMac -> objectNode {
        put("dockerDesktopVersion", value.dockerDesktopVersion)
        put("dockerEngineVersion", value.dockerEngineVersion)
        put("hardwareModelClass", value.hardwareModelClass)
        put("kind", "controlledMac")
        put("macosBuild", value.macosBuild)
        put("macosVersion", value.macosVersion)
        set("vmResources", render(value.vmResources))
      }
      is SubstrateIdentity.GithubHosted -> objectNode {
        set("advertisedResources", render(value.advertisedResources))
        put("dockerEngineVersion", value.dockerEngineVersion)
        put("kernel", value.kernel)
        put("kind", "githubHosted")
        put("runnerImageVersion", value.runnerImageVersion)
        put("runnerLabel", value.runnerLabel)
      }
    }

  private fun render(value: AdvertisedResources): ObjectNode =
    objectNode { put("cpus", value.cpus); put("memoryBytes", value.memoryBytes) }

  private fun render(value: ToolchainIdentity): ObjectNode = objectNode {
    put("gradleVersion", value.gradleVersion)
    put("jmhCoreVersion", value.jmhCoreVersion)
    put("jmhPluginVersion", value.jmhPluginVersion)
    put("kotlinCompilerVersion", value.kotlinCompilerVersion)
    put("sanitizerVersion", value.sanitizerVersion)
    put("schemaVersion", value.schemaVersion)
  }

  private fun ObjectNode.identity(): CaptureIdentity =
    CaptureIdentity(text("captureId"), text("processRunId"), text("performanceSessionId"), int("sessionSequence"))

  private fun ObjectNode.provenance(): ProvenanceRoles =
    ProvenanceRoles(git("treatment"), git("immutableHarness"), git("distributionFreezer"), git("captureRunner"))

  private fun ObjectNode.git(name: String): GitProvenance =
    objectNode(name).let { GitProvenance(it.text("gitSha"), it.get("treeClean").asBoolean()) }

  private fun ObjectNode.protocol(): ProtocolIdentity =
    ProtocolIdentity(
      sha("benchmarkSourceSha256"),
      sha("benchmarkProtocolSha256"),
      sha("qualificationPolicySha256"),
      sha("workloadTreeSha256"),
      sha("hostAdapterSha256"),
      sha("schemaSha256"),
      sha("rendererSha256"),
      sha("comparatorSha256"),
    )

  private fun ObjectNode.artifacts(): CaptureArtifacts =
    CaptureArtifacts(
      artifact("production"),
      artifact("benchmark"),
      artifact("distribution"),
      arrayNode("orderedClasspath").values().asSequence().map { it.asObject().artifact() }.toList(),
      artifact("executingRunner"),
      arrayNode("orderedRunnerClasspath").values().asSequence().map { it.asObject().artifact() }.toList(),
      arrayNode("dependencies").values().asSequence().map { value ->
        value.asObject().let { DependencyIdentity(it.text("coordinate"), it.sha("sha256")) }
      }.toList(),
      sha("rawJmhInputSha256"),
    )

  private fun ObjectNode.artifact(name: String): ArtifactIdentity = objectNode(name).artifact()

  private fun ObjectNode.artifact(): ArtifactIdentity = ArtifactIdentity(text("path"), sha("sha256"))

  private fun ObjectNode.toolchain(): ToolchainIdentity =
    ToolchainIdentity(
      text("gradleVersion"),
      text("jmhPluginVersion"),
      text("jmhCoreVersion"),
      text("kotlinCompilerVersion"),
      text("schemaVersion"),
      text("sanitizerVersion"),
    )

  private fun ObjectNode.runtime(): RuntimeIdentity =
    RuntimeIdentity(
      jdk = objectNode("jdk").let { JdkIdentity(it.sha("binarySha256"), it.text("vendor"), it.text("version"), it.stringList("jvmArguments")) },
      oci = objectNode("oci").let { OciIdentity(it.text("imageReference"), it.text("platformManifestDigest"), it.text("configDigest")) },
      linux = objectNode("linux").let { LinuxIdentity(it.text("os"), it.text("kernel"), it.text("architecture")) },
      limits = objectNode("limits").let { RuntimeLimits(it.text("cpuSet"), it.long("memoryBytes"), it.long("memorySwapBytes"), it.int("pidLimit")) },
      storage = objectNode("storage").let { StorageIdentity(it.text("distributionSource"), it.stringList("writableMounts")) },
      network = objectNode("network").let { NetworkIdentity(it.text("mode"), it.text("pullPolicy")) },
      security = objectNode("security").let { SecurityIdentity(it.text("user"), it.get("readOnlyRoot").asBoolean(), it.get("noNewPrivileges").asBoolean(), it.stringList("capabilities")) },
      environment = objectNode("environment").properties().associate { it.key to it.value.asString() },
      hostId = text("hostId"),
      substrate = objectNode("substrate").substrate(),
    )

  private fun ObjectNode.substrate(): SubstrateIdentity =
    when (text("kind")) {
      "controlledMac" ->
        SubstrateIdentity.ControlledMac(
          text("macosVersion"), text("macosBuild"), text("hardwareModelClass"),
          text("dockerDesktopVersion"), text("dockerEngineVersion"),
          objectNode("vmResources").let { AdvertisedResources(it.int("cpus"), it.long("memoryBytes")) },
        )
      "githubHosted" ->
        SubstrateIdentity.GithubHosted(
          text("runnerLabel"), text("runnerImageVersion"), text("kernel"), text("dockerEngineVersion"),
          objectNode("advertisedResources").let { AdvertisedResources(it.int("cpus"), it.long("memoryBytes")) },
        )
      else -> error("unsupported substrate")
    }

  private fun ObjectNode.logging(): LoggingProfileIdentity =
    LoggingProfileIdentity(text("profile"), sha("configurationSha256"))

  private fun ObjectNode.profile(): CaptureProfileIdentity =
    CaptureProfileIdentity(text("family"), text("identity"), sha("variantSha256"), int("forks"), int("warmupIterations"), int("measurementIterations"), text("profiler"))

  private fun ObjectNode.cell(): CaptureCell =
    CaptureCell(
      benchmark = text("benchmark"),
      parameters = objectNode("parameters").properties().associate { it.key to it.value.asString() },
      mode = text("mode"),
      unit = text("unit"),
      threads = int("threads"),
      batchSize = int("batchSize"),
      primaryMetric = objectNode("primaryMetric").let { PrimaryMetricIdentity(it.text("name"), it.text("direction")) },
      jmhResultRow = objectNode("jmhResultRow").let { JmhResultRowRef(it.text("jsonPointer"), it.sha("sha256")) },
      sampleDimensions = objectNode("sampleDimensions").let { SampleDimensions(it.int("forks"), it.int("measurementIterations"), it.int("samplesPerFork")) },
      derivedForkSummaries = arrayNode("derivedForkSummaries").values().asSequence().map { value ->
        value.asObject().let { ForkSummary(it.int("fork"), it.int("sampleCount"), it.get("score").decimalValue()) }
      }.toList(),
    )

  private fun reason(value: ProvisionalOutcomeReason): String =
    when (value) {
      ProvisionalOutcomeReason.BOUNDED_DIAGNOSTIC -> "boundedDiagnostic"
      ProvisionalOutcomeReason.GITHUB_HOSTED -> "githubHosted"
      ProvisionalOutcomeReason.INVALID_MEASUREMENT -> "invalidMeasurement"
      ProvisionalOutcomeReason.PROFILER_DIAGNOSTIC -> "profilerDiagnostic"
      ProvisionalOutcomeReason.QUALIFICATION_FAILED -> "qualificationFailed"
      ProvisionalOutcomeReason.STRUCTURAL_CANARY -> "structuralCanary"
    }

  private fun reason(value: FinalOutcomeReason): String =
    when (value) {
      FinalOutcomeReason.BOUNDED_DIAGNOSTIC -> "boundedDiagnostic"
      FinalOutcomeReason.CONTROLLED_MAC_CAMPAIGN_QUALIFIED -> "controlledMacCampaignQualified"
      FinalOutcomeReason.GITHUB_HOSTED -> "githubHosted"
      FinalOutcomeReason.INVALID_MEASUREMENT -> "invalidMeasurement"
      FinalOutcomeReason.PROFILER_DIAGNOSTIC -> "profilerDiagnostic"
      FinalOutcomeReason.QUALIFICATION_FAILED -> "qualificationFailed"
      FinalOutcomeReason.STRUCTURAL_CANARY -> "structuralCanary"
    }

  private fun reason(value: String): ProvisionalOutcomeReason =
    when (value) {
      "boundedDiagnostic" -> ProvisionalOutcomeReason.BOUNDED_DIAGNOSTIC
      "githubHosted" -> ProvisionalOutcomeReason.GITHUB_HOSTED
      "invalidMeasurement" -> ProvisionalOutcomeReason.INVALID_MEASUREMENT
      "profilerDiagnostic" -> ProvisionalOutcomeReason.PROFILER_DIAGNOSTIC
      "qualificationFailed" -> ProvisionalOutcomeReason.QUALIFICATION_FAILED
      "structuralCanary" -> ProvisionalOutcomeReason.STRUCTURAL_CANARY
      else -> error("unsupported provisional reason")
    }

  private fun objectNode(block: ObjectNode.() -> Unit): ObjectNode = JsonNodeFactory.instance.objectNode().apply(block)
  private fun <T> arrayNode(values: List<T>, render: (T) -> JsonNode): ArrayNode =
    JsonNodeFactory.instance.arrayNode().apply { values.forEach { add(render(it)) } }
  private fun stringArrayNode(values: List<String>): ArrayNode =
    JsonNodeFactory.instance.arrayNode().apply { values.forEach(::add) }
  private fun ObjectNode.text(name: String): String = get(name).asString()
  private fun ObjectNode.int(name: String): Int = get(name).asInt()
  private fun ObjectNode.long(name: String): Long = get(name).asLong()
  private fun ObjectNode.sha(name: String): Sha256 = Sha256.parse(text(name))
  private fun ObjectNode.objectNode(name: String): ObjectNode = get(name).asObject()
  private fun ObjectNode.arrayNode(name: String): ArrayNode = get(name).asArray()
  private fun ObjectNode.stringList(name: String): List<String> =
    arrayNode(name).values().asSequence().map(JsonNode::asString).toList()
}
