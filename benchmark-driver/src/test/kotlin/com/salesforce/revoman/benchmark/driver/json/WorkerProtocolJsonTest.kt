/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.json

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RunMode
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetVerificationToken
import com.salesforce.revoman.benchmark.driver.model.WorkloadRequest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class WorkerProtocolJsonTest {
  @TempDir lateinit var temporaryDirectory: Path

  @Test
  fun `protocol fixture round-trips every target fork command field as canonical bytes`() {
    val source = Path.of(requireNotNull(javaClass.getResource("/protocol/target-command-v1.json")).toURI())
    val command = BenchmarkJson.read<TargetForkCommand>(source)

    assertThat(command.protocolVersion).isEqualTo(1)
    assertThat(command.verification.targetManifest).isEqualTo("/bench/target-manifest-v1.json")
    assertThat(command.verification.targetManifestSha256)
      .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    assertThat(command.verification.targetClasspathSha256)
      .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    assertThat(command.verification.artifactStamps).hasSize(2)
    assertThat(command.verification.artifactStamps[0].logicalId).isEqualTo("adapter.jar")
    assertThat(command.verification.artifactStamps[0].executionPath).isEqualTo("/bench/adapter.jar")
    assertThat(command.verification.artifactStamps[0].sizeBytes).isEqualTo(128L)
    assertThat(command.verification.artifactStamps[0].lastModifiedMillis).isEqualTo(1_700_000_000_000L)
    assertThat(command.verification.artifactStamps[0].fileKey).isEqualTo("adapter-key")
    assertThat(command.verification.artifactStamps[1].logicalId).isEqualTo("target.jar")
    assertThat(command.verification.artifactStamps[1].executionPath).isEqualTo("/bench/target.jar")
    assertThat(command.verification.artifactStamps[1].sizeBytes).isEqualTo(4096L)
    assertThat(command.verification.artifactStamps[1].lastModifiedMillis).isEqualTo(1_700_000_000_010L)
    assertThat(command.verification.artifactStamps[1].fileKey).isNull()
    assertThat(command.adapterId).isEqualTo("postman-v1")
    assertThat(command.mode).isEqualTo(RunMode.WARM)
    assertThat(command.metricPass).isEqualTo(MetricPass.LATENCY)
    assertThat(command.workload.id).isEqualTo("simple-get")
    assertThat(command.workload.contractVersion).isEqualTo(1)
    assertThat(command.workload.fixtureRoot).isEqualTo("/bench/fixtures")
    assertThat(command.workload.baseUrl).isEqualTo("http://127.0.0.1:8080")
    assertThat(command.workload.parameters).containsExactly("region", "us-east-1", "tenant", "acme")
    assertThat(command.warmupIterations).isEqualTo(2)
    assertThat(command.measurementIterations).isEqualTo(3)
    assertThat(command.resultFile).isEqualTo("/bench/result.json")

    val firstWrite = temporaryDirectory.resolve("first.json")
    val secondWrite = temporaryDirectory.resolve("second.json")
    BenchmarkJson.write(firstWrite, command)
    BenchmarkJson.write(secondWrite, BenchmarkJson.read<TargetForkCommand>(firstWrite))

    assertThat(Files.readAllBytes(secondWrite)).isEqualTo(Files.readAllBytes(firstWrite))
  }

  @Test
  fun `semantically equal parameter maps produce identical canonical command bytes`() {
    val verification = TargetVerificationToken(
      targetManifest = "/bench/target-manifest-v1.json",
      targetManifestSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      targetClasspathSha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      artifactStamps = emptyList(),
    )
    val first = commandWith(
      parameters = linkedMapOf("zeta" to "z", "alpha" to "a"),
      verification = verification,
    )
    val second = commandWith(
      parameters = linkedMapOf("alpha" to "a", "zeta" to "z"),
      verification = verification,
    )
    val firstPath = temporaryDirectory.resolve("first-command.json")
    val secondPath = temporaryDirectory.resolve("second-command.json")

    BenchmarkJson.write(firstPath, first)
    BenchmarkJson.write(secondPath, second)

    assertThat(Files.readAllBytes(firstPath)).isEqualTo(Files.readAllBytes(secondPath))
  }

  @Test
  fun `protocol rejects duplicate artifact logical IDs`() {
    val source = Path.of(requireNotNull(javaClass.getResource("/protocol/target-command-v1.json")).toURI())
    val command = BenchmarkJson.read<TargetForkCommand>(source)
    val original = command.verification.artifactStamps.first()
    val duplicate = original.copy(executionPath = "/bench/adapter-copy.jar")

    assertThrows<IllegalArgumentException> {
      BenchmarkJson.write(
        temporaryDirectory.resolve("duplicate-artifact.json"),
        command.copy(verification = command.verification.copy(artifactStamps = listOf(original, duplicate))),
      )
    }
  }

  private fun commandWith(
    parameters: Map<String, String>,
    verification: TargetVerificationToken,
  ): TargetForkCommand =
    TargetForkCommand(
      verification = verification,
      adapterId = "postman-v1",
      mode = RunMode.COLD,
      metricPass = MetricPass.LATENCY,
      workload = WorkloadRequest(
        id = "simple-get",
        contractVersion = 1,
        fixtureRoot = "/bench/fixtures",
        baseUrl = "http://127.0.0.1:8080",
        parameters = parameters,
      ),
      warmupIterations = 0,
      measurementIterations = 0,
      resultFile = "/bench/result.json",
    )
}
