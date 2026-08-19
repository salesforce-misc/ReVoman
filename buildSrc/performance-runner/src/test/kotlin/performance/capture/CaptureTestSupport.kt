/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import java.nio.file.Files
import java.nio.file.Path
import performance.distribution.DistributionValidation
import performance.distribution.DistributionValidator
import performance.distribution.VerifiedDistribution
import performance.hash.Sha256
import performance.model.AdvertisedResources
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
import performance.support.DistributionFixture
import performance.support.DistributionFixture.Companion.EXPECTED_BENCHMARK

internal const val TEST_SHA =
  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal const val EXTRA_BENCHMARK = "example.ExtraBenchmark.work"

internal fun <T> withVerifiedDistribution(block: (DistributionFixture, VerifiedDistribution) -> T): T {
  val fixture = DistributionFixture.create()
  return try {
    val validation = DistributionValidator().validate(fixture.request())
    val distribution = (validation as DistributionValidation.Valid).distribution
    block(fixture, distribution)
  } finally {
    fixture.close()
  }
}

internal fun testProfile(
  distribution: VerifiedDistribution,
  family: CaptureProfileFamily = CaptureProfileFamily.CANARY,
  profiler: DiagnosticProfiler = DiagnosticProfiler.NONE,
  expectedCells: ExpectedCells =
    ExpectedCells(listOf(ExpectedCell(EXPECTED_BENCHMARK, mapOf("scenario" to "fixture")))),
  forks: Int = if (family == CaptureProfileFamily.CANARY) 1 else 10,
  warmupIterations: Int = if (family == CaptureProfileFamily.WARM) 5 else 0,
  measurementIterations: Int = if (family == CaptureProfileFamily.WARM) 10 else 1,
  protocolSha256: Sha256 = distribution.metadata.protocol.protocolSha256,
  selectedJavaSha256: Sha256 = distribution.metadata.classpath.javaRuntime.executableSha256,
): CaptureProfile {
  val jvmArguments =
    listOf(
      "-Xms2g",
      "-Xmx2g",
      "-Dfile.encoding=UTF-8",
      "-Duser.timezone=UTC",
      "-Duser.home=/operation/tmp",
      "-Dlog4j.configurationFile=classpath:performance/log4j2-performance.xml",
      "-Drevoman.banner=false",
    )
  val provenance = distribution.metadata.provenance
  return CaptureProfile(
    family = family,
    identity = "${family.id}-$forks-${profiler.id}",
    variantSha256 = Sha256.parse(TEST_SHA),
    forks = forks,
    warmupIterations = warmupIterations,
    measurementIterations = measurementIterations,
    batchSize = 1,
    threads = 1,
    mode = "ss",
    unit = "ms",
    profiler = profiler,
    profilerSettingsSha256 = if (profiler == DiagnosticProfiler.JFR) Sha256.parse(TEST_SHA) else null,
    profilerArguments =
      when (profiler) {
        DiagnosticProfiler.NONE -> emptyList()
        DiagnosticProfiler.GC -> listOf("gc")
        DiagnosticProfiler.JFR ->
          listOf(
            "jfr:dir={operationRoot};configName=profile;debugNonSafePoints=true;stackDepth=1024;postProcessor=$JFR_FORK_ACCUMULATOR;verbose=false",
          )
      },
    jvmArguments = jvmArguments,
    expectedCells = expectedCells,
    expectedProtocolSha256 = protocolSha256,
    selectedJavaExecutable = distribution.metadata.classpath.javaRuntime.executable,
    selectedJavaSha256 = selectedJavaSha256,
    evidence =
      CaptureEvidenceContext(
        provenance =
          ProvenanceRoles(
            treatment = provenance.treatment.toModel(),
            immutableHarness = provenance.immutableHarness.toModel(),
            distributionFreezer = provenance.distributionFreezer.toModel(),
            captureRunner = GitProvenance("4".repeat(40), true),
          ),
        protocol =
          ProtocolIdentity(
            benchmarkSourceSha256 = Sha256.parse(TEST_SHA),
            benchmarkProtocolSha256 = protocolSha256,
            qualificationPolicySha256 = Sha256.parse(TEST_SHA),
            workloadTreeSha256 = Sha256.parse(TEST_SHA),
            hostAdapterSha256 = Sha256.parse(TEST_SHA),
            schemaSha256 = Sha256.parse(TEST_SHA),
            rendererSha256 = Sha256.parse(TEST_SHA),
            comparatorSha256 = Sha256.parse(TEST_SHA),
          ),
        toolchain =
          ToolchainIdentity(
            gradleVersion = "9.7.0",
            jmhPluginVersion = "0.7.3",
            jmhCoreVersion = "1.37",
            kotlinCompilerVersion = "2.4.20-RC",
            schemaVersion = "evidence-schema-v1",
            sanitizerVersion = "privacy-v1",
          ),
        runtime =
          RuntimeIdentity(
            jdk =
              JdkIdentity(
                binarySha256 = selectedJavaSha256,
                vendor = "Eclipse Temurin",
                version = "21.0.11+10-LTS",
                jvmArguments = jvmArguments,
              ),
            oci =
              OciIdentity(
                imageReference = TEMURIN_REFERENCE,
                platformManifestDigest = TEMURIN_DIGEST,
                configDigest = TEMURIN_CONFIG_DIGEST,
              ),
            linux = LinuxIdentity("Ubuntu 24.04", "6.12.0-linuxkit", "arm64"),
            limits = RuntimeLimits("0-3", 6_442_450_944, 6_442_450_944, 512),
            storage = StorageIdentity("containerVolume", listOf("tmp", "operation-output")),
            network = NetworkIdentity("none", "never"),
            security = SecurityIdentity("10001:10001", true, true, emptyList()),
            environment = mapOf("LANG" to "C.UTF-8", "TZ" to "UTC"),
            hostId = "m4max-docker-canary-v1",
            substrate =
              SubstrateIdentity.ControlledMac(
                macosVersion = "26.6.1",
                macosBuild = "25G90",
                hardwareModelClass = "Mac16,5",
                dockerDesktopVersion = "4.45.0",
                dockerEngineVersion = "28.3.3",
                vmResources = AdvertisedResources(16, 8_589_934_592),
              ),
          ),
        logging = LoggingProfileIdentity("benchmark-noop", Sha256.parse(TEST_SHA)),
      ),
  )
}

internal fun validJmhBytes(
  benchmark: String = EXPECTED_BENCHMARK,
  rawObservation: String = "1.25",
  forks: Int = 1,
  warmupIterations: Int = 0,
  measurementIterations: Int = 1,
  params: String = "\"scenario\":\"fixture\"",
  secondaryMetrics: String = "{}",
): ByteArray {
  val forkRows =
    (1..forks).joinToString(separator = ",") {
      "[${List(measurementIterations) { rawObservation }.joinToString(",")}]"
    }
  return """
    [{
      "benchmark":"$benchmark",
      "mode":"ss",
      "threads":1,
      "forks":$forks,
      "warmupIterations":$warmupIterations,
      "warmupTime":"1 s",
      "warmupBatchSize":1,
      "measurementIterations":$measurementIterations,
      "measurementTime":"1 s",
      "measurementBatchSize":1,
      "params":{$params},
      "primaryMetric":{
        "score":NaN,
        "scoreError":NaN,
        "scoreConfidence":[NaN,NaN],
        "scorePercentiles":{"0.0":NaN,"100.0":NaN},
        "scoreUnit":"ms/op",
        "rawData":[$forkRows]
      },
      "secondaryMetrics":$secondaryMetrics
    }]
  """.trimIndent().encodeToByteArray()
}

internal fun copyJmhResource(name: String, destination: Path) {
  val bytes =
    checkNotNull(CaptureRunnerTest::class.java.getResourceAsStream("/performance/jmh/$name")) {
      "missing JMH fixture $name"
    }.use { it.readAllBytes() }
  Files.write(destination, bytes)
}

private fun performance.distribution.DistributionGitIdentity.toModel(): GitProvenance =
  GitProvenance(gitSha, treeClean)

private const val TEMURIN_DIGEST =
  "sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e"
private const val TEMURIN_CONFIG_DIGEST =
  "sha256:ad6963934ee96838c09d99f3c4df6f991cd00ed70fa8a48f7045517d7ae8991c"
private const val TEMURIN_REFERENCE = "docker.io/library/eclipse-temurin@$TEMURIN_DIGEST"
