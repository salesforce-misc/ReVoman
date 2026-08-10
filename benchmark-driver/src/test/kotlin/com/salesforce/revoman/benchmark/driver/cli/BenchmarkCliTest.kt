/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.cli

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.model.MetricPass
import com.salesforce.revoman.benchmark.driver.model.RunIntent
import com.salesforce.revoman.benchmark.driver.model.RunMode
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class BenchmarkCliTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `parser accepts every stable command without shell tokenization`() {
        val pathWithSpaces = temporaryDirectory.resolve("baseline target.json").toString()

        assertThat(BenchmarkCli.parse(arrayOf("list-workloads"))).isEqualTo(BenchmarkCommand.ListWorkloads)
        assertThat(
                BenchmarkCli.parse(
                    runArguments(
                        baseline = pathWithSpaces,
                        intent = "smoke",
                        mode = "cold",
                        metrics = "latency,allocation,peak-rss",
                    )
                )
            )
            .isInstanceOf(BenchmarkCommand.RunPaired::class.java)
        val run =
            BenchmarkCli.parse(
                runArguments(
                    baseline = pathWithSpaces,
                    intent = "smoke",
                    mode = "cold",
                    metrics = "latency,allocation,peak-rss",
                )
            ) as BenchmarkCommand.RunPaired
        assertThat(run.baseline.toString()).isEqualTo(pathWithSpaces)
        assertThat(run.intent).isEqualTo(RunIntent.SMOKE)
        assertThat(run.mode).isEqualTo(RunMode.COLD)
        assertThat(run.metricPasses)
            .containsExactly(MetricPass.LATENCY, MetricPass.ALLOCATION, MetricPass.PEAK_RSS)
            .inOrder()

        assertThat(
                BenchmarkCli.parse(
                    arrayOf(
                        "compare",
                        "--input",
                        "paired.json",
                        "--output-json",
                        "comparison.json",
                        "--output-md",
                        "comparison.md",
                    )
                )
            )
            .isEqualTo(
                BenchmarkCommand.Compare(
                    input = Path.of("paired.json"),
                    outputJson = Path.of("comparison.json"),
                    outputMarkdown = Path.of("comparison.md"),
                    enforceReleaseGates = false,
                )
            )
        assertThat(BenchmarkCli.parse(arrayOf("verify", "--input", "result.json")))
            .isEqualTo(BenchmarkCommand.Verify(Path.of("result.json")))
        assertThat(
                BenchmarkCli.parse(
                    runArguments(
                        command = "capture-baseline",
                        intent = "controlled",
                        mode = "cold",
                        metrics = "latency",
                        hostPolicy = "policy.json",
                    )
                )
            )
            .isInstanceOf(BenchmarkCommand.CaptureBaseline::class.java)
    }

    @Test
    fun `compare accepts exact valueless enforce release gates flag only`() {
        val enforced =
            BenchmarkCli.parse(
                arrayOf(
                    "compare",
                    "--input",
                    "paired.json",
                    "--output-json",
                    "comparison.json",
                    "--output-md",
                    "comparison.md",
                    "--enforce-release-gates",
                )
            ) as BenchmarkCommand.Compare

        assertThat(enforced.enforceReleaseGates).isTrue()
        listOf(
                arrayOf(
                    "compare",
                    "--input",
                    "paired.json",
                    "--output-json",
                    "comparison.json",
                    "--output-md",
                    "comparison.md",
                    "--enforce-release-gates",
                    "true",
                ),
                arrayOf(
                    "compare",
                    "--input",
                    "paired.json",
                    "--output-json",
                    "comparison.json",
                    "--output-md",
                    "comparison.md",
                    "--enforce-release-gate",
                ),
            )
            .forEach { arguments ->
                assertThrows<CliUsageException> { BenchmarkCli.parse(arguments) }
            }
    }

    @Test
    fun `atomic output set reserves aliases and rolls back the whole set after publication failure`() {
        val parent = temporaryDirectory.toRealPath()
        val input = Files.writeString(parent.resolve("input.json"), "input").toRealPath()
        val first = parent.resolve("first.json")
        val second = parent.resolve("second.md")
        var moves = 0

        val failure = assertThrows<DeliberateOutputFailure> {
            AtomicOutputSet.reserve(
                    inputs = listOf("--input" to input),
                    outputs = listOf("--output-json" to first, "--output-md" to second),
                    publish = { source, target ->
                        moves++
                        if (moves == 2) throw DeliberateOutputFailure("second publish failed")
                        Files.move(
                            source,
                            target,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    },
                )
                .use { outputSet ->
                    outputSet.prepare(0) { path -> Files.writeString(path, "json") }
                    outputSet.prepare(1) { path -> Files.writeString(path, "markdown") }
                    outputSet.publish()
                }
        }

        assertThat(failure).hasMessageThat().contains("second publish failed")
        assertThat(Files.exists(first)).isFalse()
        assertThat(Files.exists(second)).isFalse()
        assertThat(Files.readString(input)).isEqualTo("input")
        assertThrows<IllegalArgumentException> {
            AtomicOutputSet.reserve(
                inputs = listOf("--input" to input),
                outputs = listOf("--output-json" to input),
            )
        }

        val hardLink = parent.resolve("hard-link-output.json")
        Files.createLink(hardLink, input)
        assertThrows<IllegalArgumentException> {
            AtomicOutputSet.reserve(
                inputs = emptyList(),
                outputs = listOf("--output-json" to hardLink),
            )
        }

        val symlink = parent.resolve("symlink-output.json")
        Files.createSymbolicLink(symlink, parent.resolve("missing-output.json"))
        assertThrows<IllegalArgumentException> {
            AtomicOutputSet.reserve(
                inputs = emptyList(),
                outputs = listOf("--output-json" to symlink),
            )
        }

        val caseSource = Files.writeString(parent.resolve("Case-Alias.json"), "case")
        val caseAlias = parent.resolve("case-alias.json")
        if (Files.exists(caseAlias) && Files.isSameFile(caseSource, caseAlias)) {
            assertThrows<IllegalArgumentException> {
                AtomicOutputSet.reserve(
                    inputs = emptyList(),
                    outputs = listOf("--output-json" to caseAlias),
                )
            }
        }
    }

    @Test
    fun `run paired requires every exact option and rejects unknown duplicate and positional input`() {
        val complete = runArguments()
        val required =
            listOf(
                "--mode",
                "--intent",
                "--baseline",
                "--baseline-adapter",
                "--candidate",
                "--candidate-adapter",
                "--workload",
                "--blocks",
                "--forks-per-block",
                "--warmups",
                "--iterations",
                "--seed",
                "--metrics",
                "--artifacts-dir",
                "--output",
            )

        required.forEach { option ->
            val index = complete.indexOf(option)
            val failure = assertThrows<CliUsageException> {
                BenchmarkCli.parse((complete.take(index) + complete.drop(index + 2)).toTypedArray())
            }
            assertThat(failure).hasMessageThat().contains(option)
        }
        listOf(
                complete.toList() + listOf("unexpected"),
                complete.toList() + listOf("--unknown", "value"),
                complete.toList() + listOf("--blocks", "2"),
            )
            .forEach { arguments -> assertThrows<CliUsageException> { BenchmarkCli.parse(arguments.toTypedArray()) } }
    }

    @Test
    fun `controlled run requires explicit host policy while smoke records its omission`() {
        val controlled = assertThrows<CliUsageException> {
            BenchmarkCli.parse(runArguments(intent = "controlled"))
        }
        assertThat(controlled).hasMessageThat().contains("--host-policy")

        val smoke = BenchmarkCli.parse(runArguments(intent = "smoke")) as BenchmarkCommand.RunPaired
        assertThat(smoke.hostPolicy).isNull()
    }

    @Test
    fun `parser rejects invalid numeric metric and mode combinations`() {
        listOf(
                runArguments(blocks = "0"),
                runArguments(forks = "0"),
                runArguments(warmups = "-1"),
                runArguments(iterations = "0"),
                runArguments(mode = "warm", metrics = "peak-rss"),
                runArguments(mode = "retained", metrics = "latency"),
                runArguments(metrics = "latency,latency"),
                runArguments().also { arguments ->
                    arguments[arguments.indexOf("--workload") + 1] = "../outside"
                },
            )
            .forEach { arguments -> assertThrows<CliUsageException> { BenchmarkCli.parse(arguments) } }
    }

    @Test
    fun `artifact directory reservation rejects existing symlink and noncanonical parent atomically`() {
        val parent = Files.createDirectories(temporaryDirectory.resolve("parent")).toRealPath()
        val reserved = parent.resolve("new-run")

        assertThat(ArtifactDirectory.reserve(reserved)).isEqualTo(reserved)
        assertThat(Files.isDirectory(reserved)).isTrue()
        assertThrows<IllegalArgumentException> { ArtifactDirectory.reserve(reserved) }

        val broken = parent.resolve("broken")
        Files.createSymbolicLink(broken, parent.resolve("missing"))
        assertThrows<IllegalArgumentException> { ArtifactDirectory.reserve(broken) }

        val prior = Files.writeString(parent.resolve("prior-evidence"), "prior")
        val hardLink = parent.resolve("hard-link")
        Files.createLink(hardLink, prior)
        assertThrows<IllegalArgumentException> { ArtifactDirectory.reserve(hardLink) }
        assertThat(Files.readString(prior)).isEqualTo("prior")

        val alias = temporaryDirectory.resolve("alias-parent")
        Files.createSymbolicLink(alias, parent)
        assertThrows<IllegalArgumentException> { ArtifactDirectory.reserve(alias.resolve("aliased-run")) }
        assertThat(Files.exists(parent.resolve("aliased-run"))).isFalse()
    }

    private fun runArguments(
        command: String = "run-paired",
        baseline: String = "baseline.json",
        intent: String = "smoke",
        mode: String = "cold",
        metrics: String = "latency",
        blocks: String = "2",
        forks: String = "1",
        warmups: String = "0",
        iterations: String = "1",
        hostPolicy: String? = null,
    ): Array<String> =
        buildList {
                add(command)
                addAll(
                    listOf(
                        "--mode",
                        mode,
                        "--intent",
                        intent,
                        "--baseline",
                        baseline,
                        "--baseline-adapter",
                        "baseline-83f3cd70",
                        "--candidate",
                        "candidate.json",
                        "--candidate-adapter",
                        "major-v1",
                        "--workload",
                        "lifecycle.no-script-one-step.v1",
                        "--blocks",
                        blocks,
                        "--forks-per-block",
                        forks,
                        "--warmups",
                        warmups,
                        "--iterations",
                        iterations,
                        "--seed",
                        "5928239383101656625",
                        "--metrics",
                        metrics,
                        "--artifacts-dir",
                        "artifacts",
                        "--output",
                        "result.json",
                    )
                )
                hostPolicy?.let { addAll(listOf("--host-policy", it)) }
            }
            .toTypedArray()
}

private class DeliberateOutputFailure(message: String) : RuntimeException(message)
