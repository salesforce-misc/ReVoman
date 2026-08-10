/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

class BenchmarkWorkflowTest {
  @Test
  fun `controlled workflow renders protected independent checkouts`() {
    val workflow = renderedControlledWorkflow()
    assertThat(workflow.asMap("on").keys).containsExactly("workflow_dispatch")
    val dispatchInputs = workflow.asMap("on").asMap("workflow_dispatch").asMap("inputs")
    assertThat(dispatchInputs.keys)
      .containsExactly("harness_ref", "candidate_ref", "candidate_adapter", "host_policy_path")
    assertThat(dispatchInputs.asMap("candidate_adapter").asList("options"))
      .containsExactly("major-v1", "baseline-83f3cd70")
      .inOrder()

    assertThat(workflow.asMap("permissions")).containsExactly("contents", "read")
    assertThat(workflow.asMap("concurrency")["cancel-in-progress"]).isEqualTo(false)

    val job = workflow.asMap("jobs").asMap("benchmark")
    assertThat(job["environment"]).isEqualTo("performance")
    assertThat(job.asList("runs-on"))
      .containsExactly("self-hosted", "linux", "revoman-controlled-benchmark")
      .inOrder()
    assertThat(job["timeout-minutes"]).isEqualTo(180)
    assertThat(job.asMap("env")["CANDIDATE_ADAPTER"]).isEqualTo(CANDIDATE_ADAPTER)
    assertThat(job.asMap("env")["HOST_POLICY_PATH"]).isEqualTo(HOST_POLICY_PATH)
    assertThat(job.asMap("env")["RUN_ROOT"])
      .isEqualTo(
        "${'$'}{{ runner.temp }}/revoman-benchmark-unavailable-" +
          "${'$'}{{ github.run_id }}-${'$'}{{ github.run_attempt }}"
      )

    val steps = job.asList("steps").map { it.asMap() }
    val stepNames = steps.mapNotNull { it["name"] as? String }
    val validationIndex = stepNames.indexOf("Validate requested refs")
    val checkoutIndex = steps.indexOfFirst { it["uses"] == "actions/checkout@main" }
    assertThat(validationIndex).isAtLeast(0)
    assertThat(validationIndex).isLessThan(checkoutIndex)

    val checkouts = steps.filter { it["uses"] == "actions/checkout@main" }.map { it.asMap("with") }
    assertThat(checkouts.map { it["path"] })
      .containsExactly("harness", "baseline-a", "baseline-b", "candidate")
      .inOrder()
    assertThat(checkouts.map { it["ref"] })
      .containsExactly(
        HARNESS_REF,
        FIXED_BASELINE_COMMIT,
        FIXED_BASELINE_COMMIT,
        CANDIDATE_REF,
      )
      .inOrder()
    assertThat(checkouts.map { it["clean"] }.toSet()).containsExactly(true)
    assertThat(steps.single { it["uses"] == "actions/setup-java@main" }.asMap("with"))
      .containsAtLeast("distribution", "jetbrains", "java-version", 21)
    assertThat(steps.count { it["uses"] == "gradle/actions/setup-gradle@main" }).isEqualTo(1)
  }

  @Test
  fun `controlled workflow gates A-A before candidate claims`() {
    val steps = controlledSteps()
    val stepNames = steps.mapNotNull { it["name"] as? String }
    val runByName = runScriptsByName(steps)
    assertThat(runByName.getValue("Validate requested refs"))
      .contains("\"${'$'}HARNESS_REF\" =~ ^[0-9a-fA-F]{40}${'$'}")
    assertThat(runByName.getValue("Validate clean checkout identities"))
      .contains(FIXED_BASELINE_COMMIT)
    assertThat(runByName.getValue("Prepare unique run root"))
      .contains("/opt/revoman-benchmark/runs/${'$'}{GITHUB_RUN_ID}-${'$'}{GITHUB_RUN_ATTEMPT}")

    assertManifestExport(runByName.getValue("Export baseline-a target"), "baseline-a")
    assertManifestExport(runByName.getValue("Export baseline-b target"), "baseline-b")
    assertManifestExport(runByName.getValue("Export candidate target"), "candidate")

    val coldAa = runByName.getValue("Capture cold A-A")
    val warmAa = runByName.getValue("Capture warm A-A")
    val aaGate = runByName.getValue("Require cold and warm A-A gates")
    val coldCandidate = runByName.getValue("Capture cold candidate comparison")
    val warmCandidate = runByName.getValue("Capture warm candidate comparison")
    val candidateGate = runByName.getValue("Compare cold and warm candidate results")

    assertControlledRun(coldAa, command = "capture-baseline", blocks = 50, mode = "cold")
    assertControlledRun(warmAa, command = "capture-baseline", blocks = 5, mode = "warm")
    assertControlledRun(coldCandidate, command = "run-paired", blocks = 50, mode = "cold")
    assertControlledRun(warmCandidate, command = "run-paired", blocks = 5, mode = "warm")
    assertThat(coldCandidate).contains("--candidate \"${'$'}RUN_ROOT/manifests/candidate.json\"")
    assertThat(warmCandidate).contains("--candidate-adapter \"${'$'}CANDIDATE_ADAPTER\"")
    assertThat(aaGate.split("--enforce-release-gates")).hasSize(3)
    assertThat(candidateGate.split("--enforce-release-gates")).hasSize(3)

    val aaGateIndex = stepNames.indexOf("Require cold and warm A-A gates")
    val firstCandidateIndex = stepNames.indexOf("Capture cold candidate comparison")
    assertThat(aaGateIndex).isLessThan(firstCandidateIndex)
  }

  @Test
  fun `controlled workflow keeps every output in the unique run root`() {
    val steps = controlledSteps()
    val runByName = runScriptsByName(steps)
    val allRunScripts = runByName.values.joinToString("\n")
    assertThat(allRunScripts).doesNotContain("${'$'}{{ inputs.")
    assertThat(allRunScripts).contains("cold-aa.json")
    assertThat(allRunScripts).contains("warm-aa.json")
    assertThat(allRunScripts).contains("cold-candidate.json")
    assertThat(allRunScripts).contains("warm-candidate.json")
    assertThat(allRunScripts).contains("comparison-aa-cold.json")
    assertThat(allRunScripts).contains("comparison-aa-warm.json")
    assertThat(allRunScripts).contains("comparison-candidate-cold.json")
    assertThat(allRunScripts).contains("comparison-candidate-warm.json")

    val uploads = steps.filter { it["uses"] == "actions/upload-artifact@main" }
    assertThat(uploads).hasSize(2)
    assertThat(uploads.map { it["if"] }.toSet()).containsExactly("${'$'}{{ always() }}")
    assertThat(uploads.map { it.asMap("with")["path"] })
      .containsExactly(
        "${'$'}{{ env.RUN_ROOT }}/results/**",
        "${'$'}{{ env.RUN_ROOT }}/jfr/**",
      )
    assertThat(uploads.joinToString()).doesNotContain("/opt/revoman-benchmark/runs/**")
  }

  @Test
  fun `ordinary CI is structural and supplies the exported target manifest`() {
    val workflow = readWorkflow("build.yml").asMap()
    val steps = workflow.asMap("jobs").asMap("gradle").asList("steps").map { it.asMap() }
    val runByName =
      steps
        .filter { it.containsKey("run") }
        .associate { requireNotNull(it["name"] as? String) to requireNotNull(it["run"] as? String) }

    assertThat(runByName.getValue("Gradle build")).isEqualTo("./gradlew build")
    assertThat(runByName.getValue("Export current benchmark target"))
      .contains("-Pbenchmark.targetManifest=build/benchmark-target-current.json")
    val selfTest = runByName.getValue("Benchmark harness self-test")
    assertThat(selfTest).contains(":benchmark-driver:check")
    assertThat(selfTest).contains(":benchmark-driver:benchmarkHarnessSelfTest")
    assertThat(selfTest).contains("-Pbenchmark.targetManifest=build/benchmark-target-current.json")
    assertThat(selfTest).contains("-Pbenchmark.adapter=baseline-83f3cd70")

    val ordinaryRuns = runByName.values.joinToString("\n")
    assertThat(ordinaryRuns).doesNotContain("--enforce-release-gates")
    assertThat(ordinaryRuns).doesNotContain("capture-baseline")
    assertThat(ordinaryRuns).doesNotContain("run-paired")
  }

  @Test
  fun `Qodana prepares benchmark driver generated classes without running timing gates`() {
    val workflow = readWorkflow("qodana.yml").asMap()
    val steps = workflow.asMap("jobs").asMap("qodana").asList("steps").map { it.asMap() }
    val generatedSources = steps.single { it["name"] == "Generate sources for analysis" }
    val command = requireNotNull(generatedSources["run"] as? String)

    assertThat(command)
      .isEqualTo(
        "./gradlew kaptKotlin classes :benchmark-driver:kaptKotlin :benchmark-driver:classes"
      )
    assertThat(command).doesNotContain("qodanaScan")
    assertThat(command).doesNotContain("benchmarkHarnessSelfTest")
  }

  private fun assertManifestExport(script: String, targetId: String) {
    assertThat(script).contains("TARGET_ID=$targetId")
    assertThat(script).contains("TARGET_MANIFEST=\"${'$'}RUN_ROOT/manifests/$targetId.json\"")
    assertThat(script).contains("-Pbenchmark.targetId=\"${'$'}TARGET_ID\"")
    assertThat(script).contains("-Pbenchmark.targetManifest=\"${'$'}TARGET_MANIFEST\"")
  }

  private fun assertControlledRun(script: String, command: String, blocks: Int, mode: String) {
    assertThat(script).contains("\"${'$'}DRIVER\" $command")
    assertThat(script).contains("--mode $mode")
    assertThat(script).contains("--blocks $blocks")
    assertThat(script).contains("--forks-per-block 1")
    assertThat(script).contains("--host-policy \"${'$'}HOST_POLICY_PATH\"")
  }

  private fun renderedControlledWorkflow(): Map<String, Any?> =
    renderInputs(
        readWorkflow("benchmark.yml"),
        mapOf(
          "harness_ref" to HARNESS_REF,
          "candidate_ref" to CANDIDATE_REF,
          "candidate_adapter" to CANDIDATE_ADAPTER,
          "host_policy_path" to HOST_POLICY_PATH,
        ),
      )
      .asMap()

  private fun controlledSteps(): List<Map<String, Any?>> =
    renderedControlledWorkflow().asMap("jobs").asMap("benchmark").asList("steps").map { it.asMap() }

  private fun runScriptsByName(steps: List<Map<String, Any?>>): Map<String, String> =
    steps
      .filter { it.containsKey("run") }
      .associate {
        requireNotNull(it["name"] as? String) to requireNotNull(it["run"] as? String)
      }

  private fun readWorkflow(name: String): Any? =
    Files.newBufferedReader(repositoryRoot.resolve(".github/workflows/$name")).use { reader ->
      Yaml(SafeConstructor(LoaderOptions())).load<Any?>(reader)
    }

  private fun renderInputs(value: Any?, inputs: Map<String, String>): Any? =
    when (value) {
      is Map<*, *> ->
        value.entries.associate { (key, entryValue) -> key to renderInputs(entryValue, inputs) }
      is List<*> -> value.map { renderInputs(it, inputs) }
      is String ->
        INPUT_EXPRESSION.replace(value) { match ->
          requireNotNull(inputs[match.groupValues[1]]) {
            "Missing workflow render input ${match.groupValues[1]}"
          }
        }
      else -> value
    }

  @Suppress("UNCHECKED_CAST")
  private fun Any?.asMap(): Map<String, Any?> = this as Map<String, Any?>

  private fun Map<String, Any?>.asMap(key: String): Map<String, Any?> = getValue(key).asMap()

  @Suppress("UNCHECKED_CAST")
  private fun Map<String, Any?>.asList(key: String): List<Any?> = getValue(key) as List<Any?>

  private val repositoryRoot: Path = Path.of(System.getProperty("user.dir")).toRealPath()

  private companion object {
    val HARNESS_REF: String = "a".repeat(40)
    const val CANDIDATE_REF: String = "candidate/topic"
    const val CANDIDATE_ADAPTER: String = "major-v1"
    const val HOST_POLICY_PATH: String = "/opt/revoman-benchmark/policies/policy with spaces.json"
    const val FIXED_BASELINE_COMMIT: String = "83f3cd70f78ad733412d10cbc8287aaabafe7aac"
    val INPUT_EXPRESSION: Regex = Regex("\\$\\{\\{\\s*inputs\\.([a-z_]+)\\s*}}")
  }
}
