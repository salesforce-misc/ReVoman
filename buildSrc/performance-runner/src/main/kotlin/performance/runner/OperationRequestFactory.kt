/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.runner

import java.nio.file.Files
import java.nio.file.Path
import performance.campaign.CampaignRequest
import performance.campaign.ProfileFamily
import performance.campaign.SessionIdentity
import performance.capture.CaptureEvidenceContext
import performance.capture.CaptureProfile
import performance.capture.CaptureProfileFamily
import performance.capture.CaptureProfileReader
import performance.capture.CaptureRequest
import performance.capture.DiagnosticProfiler
import performance.capture.ExpectedCellsReader
import performance.compare.ComparisonInputVerifier
import performance.compare.DistributionProjection
import performance.compare.RegressionPolicy
import performance.distribution.DistributionGitIdentity
import performance.distribution.VerifiedDistribution
import performance.hash.Sha256
import performance.model.AdvertisedResources
import performance.model.CaptureIdentity
import performance.model.GitProvenance
import performance.model.JdkIdentity
import performance.model.LinuxIdentity
import performance.model.LoggingProfileIdentity
import performance.model.NetworkIdentity
import performance.model.OciIdentity
import performance.model.ProtocolIdentity
import performance.model.ProvenanceRoles
import performance.model.RuntimeIdentity
import performance.model.RuntimeLimits
import performance.model.SecurityIdentity
import performance.model.StorageIdentity
import performance.model.SubstrateIdentity
import performance.model.ToolchainIdentity
import tools.jackson.databind.node.ObjectNode

/** Verifier-owned construction of executable requests from one validated frozen distribution. */
internal object OperationRequestFactory {
  fun capture(
    flags: Map<String, String>,
    distribution: VerifiedDistribution,
  ): CaptureRequest {
    val family = CaptureProfileFamily.entries.single { it.id == flags.getValue("--profile") }
    val profiler =
      flags["--diagnostic-profiler"]?.let { raw ->
        DiagnosticProfiler.entries.single { it.id == raw }
      } ?: DiagnosticProfiler.NONE
    val forks = flags.getValue("--forks").toInt()
    val output = output(flags.getValue("--output"))
    val runtime = PrivateRuntimeBinding.read(output.resolveSibling(STATE_DIRECTORY).resolve(RUNTIME_BINDING))
    val profile =
      profile(distribution, family, forks, profiler, flags.getValue("--host-id"), runtime)
    val session = flags.getValue("--session-id")
    val sequence = flags.getValue("--sequence").toInt()
    val captureId = "$session-${family.id}-$forks-${profiler.id}-$sequence"
    return CaptureRequest(
      distribution = distribution,
      profile = profile,
      identity =
        CaptureIdentity(
          captureId = captureId,
          processRunId = "$captureId-process",
          performanceSessionId = session,
          sessionSequence = sequence,
        ),
      provisionalRoot = output,
    )
  }

  fun campaign(
    flags: Map<String, String>,
    baseline: VerifiedDistribution,
    candidate: VerifiedDistribution,
  ): CampaignRequest {
    val family = CaptureProfileFamily.entries.single { it.id == flags.getValue("--profile") }
    require(family != CaptureProfileFamily.CANARY)
    val hostId = flags.getValue("--host-id")
    val provisionalRoot = output(flags.getValue("--output"))
    val runtime =
      PrivateRuntimeBinding.read(
        provisionalRoot.resolveSibling(STATE_DIRECTORY).resolve(RUNTIME_BINDING),
      )
    Files.createDirectory(provisionalRoot)
    val baselineProfiles =
      FORK_LADDER.associateWith { forks ->
        profile(baseline, family, forks, DiagnosticProfiler.NONE, hostId, runtime)
      }
    val candidateProfiles =
      FORK_LADDER.associateWith { forks ->
        profile(candidate, family, forks, DiagnosticProfiler.NONE, hostId, runtime)
      }
    return CampaignRequest(
      baseline = baseline,
      candidate = candidate,
      profileFamily = ProfileFamily.create(family, baselineProfiles, candidateProfiles),
      session = SessionIdentity.create("${family.id}-$hostId-campaign"),
      provisionalRoot = provisionalRoot,
      regressionPolicy =
        flags["--regression-policy"]?.let { path ->
          RegressionPolicy.parse(Files.readAllBytes(Path.of(path)))
        },
    )
  }

  private fun profile(
    distribution: VerifiedDistribution,
    family: CaptureProfileFamily,
    forks: Int,
    profiler: DiagnosticProfiler,
    hostId: String,
    runtimeBinding: PrivateRuntimeBinding,
  ): CaptureProfile {
    val protocol = distribution.metadata.protocol
    val projection = ComparisonInputVerifier.distributionProjection(distribution)
    val expectedCells =
      ExpectedCellsReader.read(distribution.root.resolve(protocol.expectedCells.path), family)
    val binding =
      protocol.profiles.single { candidate -> candidate.path.endsWith("/${family.id}.json") }
    val jvmArguments = projection.profileVariants.getValue(family.id).values.first().jvmArguments
    return CaptureProfileReader
      .read(
        bytes = Files.readAllBytes(distribution.root.resolve(binding.path)),
        expectedCells = expectedCells,
        expectedProtocolSha256 = protocol.protocolSha256,
        selectedJavaExecutable = distribution.metadata.classpath.javaRuntime.executable,
        selectedJavaSha256 = distribution.metadata.classpath.javaRuntime.executableSha256,
        evidence = evidence(distribution, projection, hostId, jvmArguments, runtimeBinding),
      ).single { candidate -> candidate.forks == forks && candidate.profiler == profiler }
  }

  private fun evidence(
    distribution: VerifiedDistribution,
    projection: DistributionProjection,
    hostId: String,
    jvmArguments: List<String>,
    runtimeBinding: PrivateRuntimeBinding,
  ): CaptureEvidenceContext {
    val provenance = distribution.metadata.provenance
    val runtime = projection.runtimeDeclarations.getValue(runtimeBinding.kind.projectionKey)
    return CaptureEvidenceContext(
      provenance =
        ProvenanceRoles(
          treatment = provenance.treatment.toModel(),
          immutableHarness = provenance.immutableHarness.toModel(),
          distributionFreezer = provenance.distributionFreezer.toModel(),
          captureRunner = provenance.immutableHarness.toModel(),
        ),
      protocol =
        ProtocolIdentity(
          benchmarkSourceSha256 = projection.benchmarkSourceSha256,
          benchmarkProtocolSha256 = projection.protocolSha256,
          qualificationPolicySha256 =
            projection.qualificationPolicies.getValue(runtimeBinding.kind.qualificationKey),
          workloadTreeSha256 = projection.workloadTreeSha256,
          hostAdapterSha256 = projection.adapterSha256,
          schemaSha256 = projection.captureSchemaSha256,
          rendererSha256 = projection.rendererSha256,
          comparatorSha256 = projection.comparatorSha256,
        ),
      toolchain =
        ToolchainIdentity(
          gradleVersion = projection.toolIdentities.getValue("gradle"),
          jmhPluginVersion = projection.toolIdentities.getValue("jmhGradlePlugin"),
          jmhCoreVersion = projection.toolIdentities.getValue("jmhCore"),
          kotlinCompilerVersion = projection.toolIdentities.getValue("kotlinCompiler"),
          schemaVersion = "evidence-schema-v1",
          sanitizerVersion = "privacy-v1",
        ),
      runtime =
        RuntimeIdentity(
          jdk =
            JdkIdentity(
              binarySha256 = runtime.jdkBinarySha256,
              vendor = runtime.jdkVendor,
              version = runtime.jdkVersion,
              jvmArguments = jvmArguments,
            ),
          oci =
            OciIdentity(
              imageReference = runtime.imageReference,
              platformManifestDigest = runtime.platformManifestDigest,
              configDigest = runtime.configDigest,
            ),
          linux =
            LinuxIdentity(
              runtimeBinding.linuxOs,
              runtimeBinding.linuxKernel,
              runtime.architecture,
            ),
          limits =
            RuntimeLimits(
              cpuSet = runtime.cpuSet,
              memoryBytes = runtime.memoryBytes,
              memorySwapBytes = runtime.memorySwapBytes,
              pidLimit = runtime.pidLimit,
            ),
          storage = StorageIdentity("containerVolume", listOf("tmp", "operation-output")),
          network = NetworkIdentity("none", "never"),
          security =
            SecurityIdentity(
              user = runtime.user,
              readOnlyRoot = runtime.readOnlyRoot,
              noNewPrivileges = runtime.noNewPrivileges,
              capabilities = emptyList(),
            ),
          environment = runtime.environment,
          hostId = hostId,
          substrate = runtimeBinding.substrate,
        ),
      logging = LoggingProfileIdentity("benchmark-noop", projection.loggingConfigurationSha256),
    )
  }

  private fun output(raw: String): Path {
    val output = Path.of(raw).toAbsolutePath().normalize()
    require(output.fileName != null && SAFE_ID.matches(output.fileName.toString()))
    require(!Files.exists(output) && Files.isDirectory(output.parent) && !Files.isSymbolicLink(output.parent))
    return output
  }

  private fun DistributionGitIdentity.toModel(): GitProvenance = GitProvenance(gitSha, treeClean)

  private val FORK_LADDER = listOf(10, 20, 40)
  private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
  private const val STATE_DIRECTORY = "state"
  private const val RUNTIME_BINDING = "private-runtime.json"
}

private enum class PrivateSubstrateKind(
  val projectionKey: String,
  val qualificationKey: String,
) {
  CONTROLLED_MAC("controlledMac", "controlledMacBoundedDiagnostic"),
  GITHUB_HOSTED("githubHosted", "githubHosted"),
}

private data class PrivateRuntimeBinding(
  val kind: PrivateSubstrateKind,
  val linuxOs: String,
  val linuxKernel: String,
  val substrate: SubstrateIdentity,
) {
  companion object {
    fun read(path: Path): PrivateRuntimeBinding {
      require(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
      val bytes = Files.readAllBytes(path)
      val document = performance.json.CanonicalJson.parseStrict(bytes).asObject()
      require(performance.json.CanonicalJson.encode(document).contentEquals(bytes))
      require(document.text("schemaVersion") == "private-runtime-binding-v1")
      val linux = document.objectNode("linux")
      require(linux.text("architecture") == "arm64")
      val substrate = document.objectNode("substrate")
      val kind =
        when (substrate.text("kind")) {
          "controlledMac" -> PrivateSubstrateKind.CONTROLLED_MAC
          "githubHosted" -> PrivateSubstrateKind.GITHUB_HOSTED
          else -> error("unsupported private substrate")
        }
      val identity =
        when (kind) {
          PrivateSubstrateKind.CONTROLLED_MAC ->
            SubstrateIdentity.ControlledMac(
              macosVersion = substrate.text("macosVersion"),
              macosBuild = substrate.text("macosBuild"),
              hardwareModelClass = substrate.text("hardwareModelClass"),
              dockerDesktopVersion = substrate.text("dockerDesktopVersion"),
              dockerEngineVersion = substrate.text("dockerEngineVersion"),
              vmResources = substrate.resources("vmResources"),
            )
          PrivateSubstrateKind.GITHUB_HOSTED ->
            SubstrateIdentity.GithubHosted(
              runnerLabel = substrate.text("runnerLabel"),
              runnerImageVersion = substrate.text("runnerImageVersion"),
              kernel = substrate.text("kernel"),
              dockerEngineVersion = substrate.text("dockerEngineVersion"),
              advertisedResources = substrate.resources("advertisedResources"),
            )
        }
      return PrivateRuntimeBinding(kind, linux.text("os"), linux.text("kernel"), identity)
    }

    private fun ObjectNode.resources(name: String): AdvertisedResources =
      objectNode(name).let { AdvertisedResources(it.get("cpus").asInt(), it.get("memoryBytes").asLong()) }

    private fun ObjectNode.text(name: String): String = get(name).asString().also { require(it.isNotBlank()) }
    private fun ObjectNode.objectNode(name: String): ObjectNode = get(name).asObject()
  }
}
