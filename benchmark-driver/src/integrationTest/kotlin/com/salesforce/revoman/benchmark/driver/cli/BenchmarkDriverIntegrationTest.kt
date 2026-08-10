/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.cli

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.BenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.TargetManifest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BenchmarkDriverIntegrationTest {
    @TempDir lateinit var temporaryDirectory: Path
    private var lastError: String = ""

    @Test
    fun `smoke campaign runs two exported targets verifies structurally and cannot pass release gates`() {
        val source = Path.of(requiredProperty("revoman.benchmark.targetManifest")).toRealPath()
        val exported = BenchmarkJson.read<TargetManifest>(source)
        val baseline =
            writeTarget(
                "baseline.json",
                exported.copy(
                    targetId = "baseline",
                    gitCommit = "83f3cd70f78ad733412d10cbc8287aaabafe7aac",
                ),
            )
        val candidate = writeTarget("candidate.json", exported.copy(targetId = "candidate"))
        val artifacts = temporaryRoot().resolve("artifacts")
        val result = temporaryRoot().resolve("smoke.json")
        val comparison = temporaryRoot().resolve("comparison.json")
        val markdown = temporaryRoot().resolve("comparison.md")

        val arguments =
            runArguments(baseline, candidate, artifacts, result)
                .also { values -> values[values.indexOf("--forks-per-block") + 1] = "2" }
        val runExit = execute(arguments)

        assertWithMessage(lastError).that(runExit).isEqualTo(CliExitCode.SUCCESS)
        assertThat(Files.isRegularFile(result)).isTrue()
        val paired = BenchmarkJson.read<BenchmarkResultV1>(result)
        assertThat(paired.targets.map { it.id }).containsExactly("baseline", "candidate")
        assertThat(paired.workloads.single().metricSeries.single().blocks!!.count { it.accepted })
            .isEqualTo(2)
        assertThat(
                paired.workloads.single().metricSeries.single().blocks!!.map {
                    it.observations.size
                }
            )
            .containsExactly(4, 4)
        assertThat(execute(arrayOf("verify", "--input", result.toString())))
            .isEqualTo(CliExitCode.SUCCESS)
        val forgedPaired = temporaryRoot().resolve("forged-paired.json")
        Files.writeString(
            forgedPaired,
            Files.readString(result).replace(
                paired.harness.distributionSha256,
                "0".repeat(64),
            ),
        )
        assertThat(execute(arrayOf("verify", "--input", forgedPaired.toString())))
            .isEqualTo(CliExitCode.INVALID_INPUT)
        assertThat(lastError).contains("ordered artifact snapshot")
        assertThat(
                execute(
                    arrayOf(
                        "compare",
                        "--input",
                        result.toString(),
                        "--output-json",
                        comparison.toString(),
                        "--output-md",
                        markdown.toString(),
                    )
                )
            )
            .isEqualTo(CliExitCode.SUCCESS)
        assertThat(Files.readString(markdown)).contains("Overall: INCONCLUSIVE")

        val enforcedComparison = temporaryRoot().resolve("comparison-enforced.json")
        val enforcedMarkdown = temporaryRoot().resolve("comparison-enforced.md")
        assertThat(
                execute(
                    arrayOf(
                        "compare",
                        "--input",
                        result.toString(),
                        "--output-json",
                        enforcedComparison.toString(),
                        "--output-md",
                        enforcedMarkdown.toString(),
                        "--enforce-release-gates",
                    )
                )
            )
            .isEqualTo(CliExitCode.GATE_NOT_PASSED)
    }

    @Test
    fun `warm allocation smoke attaches each normalized JMH target only to its actual role`() {
        val source = Path.of(requiredProperty("revoman.benchmark.targetManifest")).toRealPath()
        val exported = BenchmarkJson.read<TargetManifest>(source)
        val baseline = writeTarget("warm-baseline.json", exported.copy(targetId = "warm-baseline"))
        val candidate = writeTarget("warm-candidate.json", exported.copy(targetId = "warm-candidate"))
        val root = temporaryRoot()
        val arguments =
            runArguments(
                    baseline,
                    candidate,
                    root.resolve("warm-artifacts"),
                    root.resolve("warm.json"),
                )
                .toMutableList()
                .apply {
                    set(indexOf("--mode") + 1, "warm")
                    set(indexOf("--blocks") + 1, "1")
                    set(indexOf("--metrics") + 1, "allocation")
                }
                .toTypedArray()

        val exit = execute(arguments)

        assertWithMessage(lastError).that(exit).isEqualTo(CliExitCode.SUCCESS)
        val result = BenchmarkJson.read<BenchmarkResultV1>(root.resolve("warm.json"))
        val series = result.workloads.single().metricSeries.single()
        val observations = series.blocks!!.single().observations
        assertThat(observations.map { it.targetId })
            .containsExactly("warm-baseline", "warm-candidate")
        assertThat(observations.map { it.processId }.distinct()).hasSize(2)
        assertThat(series.artifacts.map { it.logicalId })
            .containsExactly(
                "warm-allocation-block-0-role-baseline-fork-0-raw.json",
                "warm-allocation-block-0-role-baseline-fork-0-normalized.json",
                "warm-allocation-block-0-role-baseline-fork-0-output.txt",
                "warm-allocation-block-0-role-candidate-fork-0-raw.json",
                "warm-allocation-block-0-role-candidate-fork-0-normalized.json",
                "warm-allocation-block-0-role-candidate-fork-0-output.txt",
            )
    }

    @Test
    fun `compare rejects single target JMH while verify accepts its strict schema`() {
        val jmh = resourcePath("/jmh-result/v1/minimal-valid.json")
        val comparison = temporaryRoot().resolve("comparison.json")
        val markdown = temporaryRoot().resolve("comparison.md")

        assertThat(execute(arrayOf("verify", "--input", jmh.toString())))
            .isEqualTo(CliExitCode.SUCCESS)
        val forgedJmh = temporaryRoot().resolve("forged-jmh.json")
        Files.writeString(
            forgedJmh,
            Files.readString(jmh).replace(
                "f1503aabfaea39746d0950fac44a21aa8a97b3946dcbb2ecc98631cb28f10902",
                "0".repeat(64),
            ),
        )
        assertThat(execute(arrayOf("verify", "--input", forgedJmh.toString())))
            .isEqualTo(CliExitCode.INVALID_INPUT)
        assertThat(lastError).contains("ordered artifact snapshot")
        assertThat(
                execute(
                    arrayOf(
                        "compare",
                        "--input",
                        jmh.toString(),
                        "--output-json",
                        comparison.toString(),
                        "--output-md",
                        markdown.toString(),
                    )
                )
            )
            .isEqualTo(CliExitCode.INVALID_INPUT)
    }

    @Test
    fun `verify rejects a dirty target manifest without making dirty smoke or JMH structurally invalid`() {
        val source = Path.of(requiredProperty("revoman.benchmark.targetManifest")).toRealPath()
        val dirtyTarget =
            writeTarget(
                "dirty-target.json",
                BenchmarkJson.read<TargetManifest>(source).copy(
                    targetId = "dirty-target",
                    dirty = true,
                ),
            )

        assertThat(execute(arrayOf("verify", "--input", dirtyTarget.toString())))
            .isEqualTo(CliExitCode.INVALID_INPUT)
        assertThat(lastError).contains("requires a clean target")
    }

    @Test
    fun `capture baseline rejects abbreviated commit and wrong adapter before reserving artifacts`() {
        val source = Path.of(requiredProperty("revoman.benchmark.targetManifest")).toRealPath()
        val exported = BenchmarkJson.read<TargetManifest>(source)
        val baseline = writeTarget("baseline-short.json", exported.copy(targetId = "baseline", gitCommit = "83f3cd70"))
        val candidate = writeTarget("candidate-short.json", exported.copy(targetId = "candidate", gitCommit = "83f3cd70"))
        val artifacts = temporaryRoot().resolve("capture-artifacts")
        val policy = resourcePath("/host/valid.json")
        val arguments =
            runArguments(baseline, candidate, artifacts, temporaryRoot().resolve("capture.json"))
                .toMutableList()
                .apply {
                    this[0] = "capture-baseline"
                    set(indexOf("--intent") + 1, "controlled")
                    addAll(listOf("--host-policy", policy.toString()))
                }
                .toTypedArray()

        assertThat(execute(arguments)).isEqualTo(CliExitCode.INVALID_INPUT)
        assertThat(lastError).contains("gitCommit")
        assertThat(Files.exists(artifacts)).isFalse()

        val fixedCommit = "83f3cd70f78ad733412d10cbc8287aaabafe7aac"
        val pinnedBaseline =
            writeTarget(
                "baseline-pinned.json",
                exported.copy(targetId = "pinned-baseline", gitCommit = fixedCommit, dirty = false),
            )
        val pinnedCandidate =
            writeTarget(
                "candidate-pinned.json",
                exported.copy(targetId = "pinned-candidate", gitCommit = fixedCommit, dirty = false),
            )
        val wrongAdapterArtifacts = temporaryRoot().resolve("wrong-adapter-artifacts")
        val wrongAdapter =
            runArguments(
                    pinnedBaseline,
                    pinnedCandidate,
                    wrongAdapterArtifacts,
                    temporaryRoot().resolve("wrong-adapter.json"),
                )
                .also { values ->
                    values[0] = "capture-baseline"
                    values[values.indexOf("--intent") + 1] = "controlled"
                    values[values.indexOf("--candidate-adapter") + 1] = "major-v1"
                }
                .toMutableList()
                .apply { addAll(listOf("--host-policy", policy.toString())) }
                .toTypedArray()
        assertThat(execute(wrongAdapter)).isEqualTo(CliExitCode.INVALID_INPUT)
        assertThat(lastError).contains("candidate adapter baseline-83f3cd70")
        assertThat(Files.exists(wrongAdapterArtifacts)).isFalse()
    }

    @Test
    fun `capture baseline requires identical path-free classpaths while ignoring execution paths`() {
        val source = Path.of(requiredProperty("revoman.benchmark.targetManifest")).toRealPath()
        val exported = BenchmarkJson.read<TargetManifest>(source)
        val fixedCommit = "83f3cd70f78ad733412d10cbc8287aaabafe7aac"
        val baselineModel =
            exported.copy(targetId = "capture-baseline-a", gitCommit = fixedCommit, dirty = false)
        val baseline = writeTarget("capture-classpath-baseline.json", baselineModel)
        val mismatchedCandidate =
            writeTarget(
                "capture-classpath-mismatch.json",
                baselineModel.copy(
                    targetId = "capture-baseline-b-mismatch",
                    classpath =
                        baselineModel.classpath.mapIndexed { index, artifact ->
                            if (index == 0) artifact.copy(logicalId = "${artifact.logicalId}-other")
                            else artifact
                        },
                ),
            )
        val mismatchArtifacts = temporaryRoot().resolve("capture-classpath-mismatch-artifacts")

        assertThat(
                execute(
                    captureArguments(
                        baseline,
                        mismatchedCandidate,
                        mismatchArtifacts,
                        temporaryRoot().resolve("capture-classpath-mismatch.json.out"),
                    )
                )
            )
            .isEqualTo(CliExitCode.INVALID_INPUT)
        assertThat(lastError).contains("identical path-free classpath")
        assertThat(Files.exists(mismatchArtifacts)).isFalse()

        val relocatedArtifact =
            baselineModel.classpath.first().let { artifact ->
                val relocated = temporaryRoot().resolve("relocated-${Path.of(artifact.executionPath).fileName}")
                Files.copy(Path.of(artifact.executionPath), relocated)
                artifact.copy(executionPath = relocated.toRealPath().toString())
            }
        val relocatedCandidate =
            writeTarget(
                "capture-classpath-relocated.json",
                baselineModel.copy(
                    targetId = "capture-baseline-b-relocated",
                    classpath = listOf(relocatedArtifact) + baselineModel.classpath.drop(1),
                ),
            )
        val relocatedArtifacts = temporaryRoot().resolve("capture-classpath-relocated-artifacts")

        assertThat(
                execute(
                    captureArguments(
                        baseline,
                        relocatedCandidate,
                        relocatedArtifacts,
                        temporaryRoot().resolve("capture-classpath-relocated.json.out"),
                    )
                )
            )
            .isEqualTo(CliExitCode.INVALID_INPUT)
        assertThat(lastError).contains("requires at least 50 accepted blocks")
        assertThat(Files.exists(relocatedArtifacts)).isFalse()
    }

    private fun execute(arguments: Array<String>): Int {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exit = BenchmarkDriverApplication.execute(
            arguments = arguments,
            output = PrintStream(stdout),
            error = PrintStream(stderr),
            installationRoot = installationRoot(),
        )
        lastError = stderr.toString()
        return exit
    }

    private fun runArguments(
        baseline: Path,
        candidate: Path,
        artifacts: Path,
        result: Path,
    ): Array<String> =
        arrayOf(
            "run-paired",
            "--mode",
            "cold",
            "--intent",
            "smoke",
            "--baseline",
            baseline.toString(),
            "--baseline-adapter",
            requiredProperty("revoman.benchmark.adapter"),
            "--candidate",
            candidate.toString(),
            "--candidate-adapter",
            requiredProperty("revoman.benchmark.adapter"),
            "--workload",
            "lifecycle.no-script-one-step.v1",
            "--blocks",
            "2",
            "--forks-per-block",
            "1",
            "--warmups",
            "0",
            "--iterations",
            "1",
            "--seed",
            "5928239383101656625",
            "--metrics",
            "latency",
            "--artifacts-dir",
            artifacts.toString(),
            "--output",
            result.toString(),
        )

    private fun captureArguments(
        baseline: Path,
        candidate: Path,
        artifacts: Path,
        result: Path,
    ): Array<String> =
        runArguments(baseline, candidate, artifacts, result)
            .toMutableList()
            .apply {
                this[0] = "capture-baseline"
                set(indexOf("--intent") + 1, "controlled")
                addAll(listOf("--host-policy", resourcePath("/host/valid.json").toString()))
            }
            .toTypedArray()

    private fun writeTarget(name: String, manifest: TargetManifest): Path =
        temporaryRoot().resolve(name).also { BenchmarkJson.write(it, manifest) }.toRealPath()

    private fun temporaryRoot(): Path = temporaryDirectory.toRealPath()

    private fun installationRoot(): Path {
        val working = Path.of(System.getProperty("user.dir")).toRealPath()
        return listOf(
                working.resolve("build/install/benchmark-driver"),
                working.resolve("benchmark-driver/build/install/benchmark-driver"),
            )
            .first(Files::isDirectory)
            .toRealPath()
    }

    private fun resourcePath(name: String): Path {
        val working = Path.of(System.getProperty("user.dir")).toRealPath()
        val relative = name.removePrefix("/")
        return listOf(
                working.resolve("src/test/resources/$relative"),
                working.resolve("benchmark-driver/src/test/resources/$relative"),
            )
            .first(Files::isRegularFile)
            .toRealPath()
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Missing system property $name" }
}
