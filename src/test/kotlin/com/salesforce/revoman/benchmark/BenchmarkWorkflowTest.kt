/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
    assertThat(dispatchInputs.asMap("candidate_ref")["required"]).isEqualTo(true)
    assertThat(dispatchInputs.asMap("candidate_ref")).doesNotContainKey("default")

    assertThat(workflow.asMap("permissions")).containsExactly("contents", "read")
    assertThat(workflow.asMap("concurrency")["cancel-in-progress"]).isEqualTo(false)

    val job = workflow.asMap("jobs").asMap("benchmark")
    assertThat(job["environment"]).isEqualTo("performance")
    assertThat(job.asList("runs-on"))
      .containsExactly("self-hosted", "linux", "revoman-controlled-benchmark")
      .inOrder()
    assertThat(job["timeout-minutes"]).isEqualTo(180)
    assertThat(job.asMap("env")["CANDIDATE_ADAPTER"]).isEqualTo(CANDIDATE_ADAPTER)
    assertThat(job.asMap("env")["CANDIDATE_REF"]).isEqualTo(CANDIDATE_REF)
    assertThat(job.asMap("env")["HOST_POLICY_PATH"]).isEqualTo(HOST_POLICY_PATH)
    assertThat(job.asMap("env")["RUN_ROOT"])
      .isEqualTo(
        "/tmp/revoman-benchmark-unavailable-" +
          "${'$'}{{ github.run_id }}-${'$'}{{ github.run_attempt }}"
      )

    val steps = job.asList("steps").map { it.asMap() }
    val stepNames = steps.mapNotNull { it["name"] as? String }
    val validationIndex = stepNames.indexOf("Validate requested refs")
    val checkoutIndex = steps.indexOfFirst { it["uses"] == CHECKOUT_ACTION }
    assertThat(validationIndex).isAtLeast(0)
    assertThat(validationIndex).isLessThan(checkoutIndex)

    val checkouts = steps.filter { it["uses"] == CHECKOUT_ACTION }.map { it.asMap("with") }
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
    assertThat(steps.single { it["uses"] == SETUP_JAVA_ACTION }.asMap("with"))
      .containsAtLeast("distribution", "jetbrains", "java-version", 21)
    assertThat(steps.count { it["uses"] == SETUP_GRADLE_ACTION }).isEqualTo(1)
  }

  @Test
  fun `controlled workflow pins every external action by reviewed commit`() {
    val uses = controlledSteps().mapNotNull { it["uses"] as? String }

    assertThat(uses.count { it == CHECKOUT_ACTION }).isEqualTo(4)
    assertThat(uses.count { it == SETUP_JAVA_ACTION }).isEqualTo(1)
    assertThat(uses.count { it == SETUP_GRADLE_ACTION }).isEqualTo(1)
    assertThat(uses.count { it == UPLOAD_ARTIFACT_ACTION }).isEqualTo(2)
    assertThat(uses.toSet())
      .containsExactly(
        CHECKOUT_ACTION,
        SETUP_JAVA_ACTION,
        SETUP_GRADLE_ACTION,
        UPLOAD_ARTIFACT_ACTION,
      )
  }

  @Test
  fun `controlled workflow validates immutable harness and candidate identities`() {
    val steps = controlledSteps()
    val runByName = runScriptsByName(steps)
    val refValidation = runByName.getValue("Validate requested refs")
    assertThat(refValidation).contains("\"${'$'}HARNESS_REF\" =~ ^[0-9a-fA-F]{40}${'$'}")
    assertThat(refValidation).contains("\"${'$'}CANDIDATE_REF\" =~ ^[0-9a-fA-F]{40}${'$'}")
    val identityValidation = runByName.getValue("Validate clean checkout identities")
    assertThat(identityValidation).contains(FIXED_BASELINE_COMMIT)
    assertThat(identityValidation)
      .contains(
        "git -C \"${'$'}GITHUB_WORKSPACE/candidate\" rev-parse HEAD)\" == " +
          "\"${'$'}CANDIDATE_REF\""
      )
    assertThat(runByName.getValue("Prepare unique run root"))
      .contains("/opt/revoman-benchmark/runs/${'$'}{GITHUB_RUN_ID}-${'$'}{GITHUB_RUN_ATTEMPT}")

    assertManifestExport(runByName.getValue("Export baseline-a target"), "baseline-a")
    assertManifestExport(runByName.getValue("Export baseline-b target"), "baseline-b")
    assertManifestExport(runByName.getValue("Export candidate target"), "candidate")
  }

  @Test
  fun `controlled workflow uses exact executable campaign argv`() {
    val steps = controlledSteps()
    assertSingleInvocation(
      steps,
      "Capture cold A-A",
      "capture-baseline",
      coldCampaignOptions(
        candidate = "baseline-b",
        candidateAdapter = "baseline-83f3cd70",
        suffix = "aa",
      ),
    )
    assertSingleInvocation(
      steps,
      "Capture warm A-A",
      "capture-baseline",
      warmCampaignOptions(
        candidate = "baseline-b",
        candidateAdapter = "baseline-83f3cd70",
        suffix = "aa",
      ),
    )
    assertSingleInvocation(
      steps,
      "Capture cold candidate comparison",
      "run-paired",
      coldCampaignOptions(
        candidate = "candidate",
        candidateAdapter = "${'$'}CANDIDATE_ADAPTER",
        suffix = "candidate",
      ),
    )
    assertSingleInvocation(
      steps,
      "Capture warm candidate comparison",
      "run-paired",
      warmCampaignOptions(
        candidate = "candidate",
        candidateAdapter = "${'$'}CANDIDATE_ADAPTER",
        suffix = "candidate",
      ),
    )
  }

  @Test
  fun `controlled workflow gates A-A before default-success candidate steps`() {
    val steps = controlledSteps()
    val stepNames = steps.mapNotNull { it["name"] as? String }
    val candidateSteps = steps.filter { it["name"] in CANDIDATE_MEASUREMENT_STEPS }
    assertThat(candidateSteps.map { it["name"] })
      .containsExactlyElementsIn(CANDIDATE_MEASUREMENT_STEPS)
    candidateSteps.forEach { step -> assertThat(step).doesNotContainKey("if") }

    val aaGateIndex = stepNames.indexOf("Require cold and warm A-A gates")
    val firstCandidateIndex = stepNames.indexOf("Capture cold candidate comparison")
    assertThat(aaGateIndex).isLessThan(firstCandidateIndex)
  }

  @Test
  fun `controlled workflow uses exact enforced comparison argv`() {
    assertComparisonStep(
      "Require cold and warm A-A gates",
      expectedComparisonOptions(prefix = "aa", inputSuffix = "aa"),
    )
    assertComparisonStep(
      "Compare cold and warm candidate results",
      expectedComparisonOptions(prefix = "candidate", inputSuffix = "candidate"),
    )
  }

  @Test
  fun `controlled Bash steps match exact reviewed script contracts`() {
    val steps = unverifiedControlledSteps()
    val bashStepNames = bashRunScriptEntries(steps).map { it.first }

    assertThat(RUN_SCRIPT_CONTRACTS.keys).containsExactlyElementsIn(bashStepNames).inOrder()
    assertRunScriptContracts(steps)
  }

  @Test
  fun `run script contracts reject every executable mutation`() {
    val steps = unverifiedControlledSteps()
    val mutations =
      listOf(
        mutateRunScript(steps, "Capture cold A-A") { script ->
          script.replace("\"${'$'}DRIVER\"", "\"${'$'}{DRIVER}\"")
        },
        mutateRunScript(steps, "Capture cold A-A") { script ->
          script.replace(
            "\"${'$'}RUN_ROOT/manifests/baseline-a.json\"",
            "\"${'$'}{RUN_ROOT:-/tmp}/manifests/baseline-a.json\"",
          )
        },
        mutateRunScript(steps, "Capture cold A-A") { script ->
          script.replace(
            "\"${'$'}HOST_POLICY_PATH\"",
            "\"${'$'}{HOST_POLICY_PATH:-/tmp/policy.json}\"",
          )
        },
        mutateRunScript(steps, "Capture cold candidate comparison") { script ->
          script.replace(
            "\"${'$'}CANDIDATE_ADAPTER\"",
            "\"${'$'}{CANDIDATE_ADAPTER:-baseline-83f3cd70}\"",
          )
        },
        mutateRunScript(steps, "Capture cold A-A") { script ->
          script.replace("\"${'$'}DRIVER\"", "'${'$'}DRIVER'")
        },
        mutateRunScript(steps, "Capture cold A-A") { script -> script + "true\n" },
        mutateRunScript(steps, "Capture cold A-A") { script ->
          script.replace("set -euo pipefail\n", "set -euo pipefail\n# changed\n")
        },
        mutateRunScript(steps, "Capture cold A-A") { script ->
          script.replace(DRIVER_ASSIGNMENT, "DRIVER=/bin/true")
        },
        mutateRunScript(steps, "Install benchmark driver") { script ->
          script.replace(" \\\n", "\n")
        },
        steps + steps.single { it["name"] == "Validate requested refs" },
        steps.filterNot { it["name"] == "Validate requested refs" },
      )

    mutations.forEach { mutation ->
      assertThrows<AssertionError> { assertRunScriptContracts(mutation) }
    }
  }

  @Test
  fun `workflow shell parser rejects comment decoys and duplicate executable options`() {
    val commentDecoy =
      """
      # "${'$'}DRIVER" run-paired --mode cold --blocks 50
      "${'$'}DRIVER" run-paired --mode cold \
        --blocks 49 # --blocks 50
      """
        .trimIndent()
    assertThrows<IllegalArgumentException> {
      WorkflowShellParser.benchmarkInvocations(commentDecoy)
    }

    val duplicate = "\"${'$'}DRIVER\" run-paired --blocks 49 --blocks 50"
    assertThrows<IllegalArgumentException> {
      WorkflowShellParser.benchmarkInvocations(duplicate)
    }
  }

  @Test
  fun `workflow shell parser rejects appended or conditional extra commands`() {
    val expected = "\"${'$'}DRIVER\" run-paired --blocks 50"
    listOf(";", "&&", "||").forEach { operator ->
      val adversarial = "$expected $operator \"${'$'}DRIVER\" run-paired --blocks 1"
      assertThrows<IllegalArgumentException> {
        WorkflowShellParser.benchmarkInvocations(adversarial)
      }
    }
  }

  @Test
  fun `workflow shell parser rejects driver overrides`() {
    val adversarial = "DRIVER=/bin/true\n\"${'$'}DRIVER\" run-paired --blocks 50"

    assertThrows<IllegalArgumentException> {
      WorkflowShellParser.benchmarkInvocations(adversarial)
    }
  }

  @Test
  fun `workflow shell parser preserves physical continuation semantics`() {
    val trailingSpaceContinuation = "\"${'$'}DRIVER\" run-paired --mode cold \\   \n  --blocks 50"
    val commentContinuation =
      "\"${'$'}DRIVER\" run-paired --mode cold \\\n" +
        "# comment changes the continued Bash command\n" +
        "  --blocks 50"

    listOf(trailingSpaceContinuation, commentContinuation).forEach { adversarial ->
      assertThrows<IllegalArgumentException> {
        WorkflowShellParser.benchmarkInvocations(adversarial)
      }
    }
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

    val uploads = steps.filter { it["uses"] == UPLOAD_ARTIFACT_ACTION }
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
    assertThat(selfTest).contains(":benchmark-driver:integrationTest")
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

  private fun assertSingleInvocation(
    steps: List<Map<String, Any?>>,
    stepName: String,
    command: String,
    expectedOptions: Map<String, String>,
  ) {
    val parsed = WorkflowShellParser.parse(stepByName(steps, stepName).getValue("run") as String)
    assertThat(parsed.statements)
      .containsExactly(
        ShellLiteral(STRICT_MODE_LINE),
        ShellLiteral(DRIVER_ASSIGNMENT),
        BenchmarkInvocation(command, expectedOptions, emptySet()),
      )
      .inOrder()
  }

  private fun assertComparisonStep(
    stepName: String,
    expectedByMode: Map<String, Map<String, String>>,
  ) {
    val script = stepByName(controlledSteps(), stepName).getValue("run") as String
    val failureBody =
      when (stepName) {
        "Require cold and warm A-A gates" ->
          listOf(
            ShellLiteral(
              "printf '%s\\n' '# INCONCLUSIVE' '' " +
                "'Candidate measurement was not started because cold or warm A/A did not pass.' " +
                "> \"${'$'}RUN_ROOT/results/INCONCLUSIVE.md\""
            ),
            ShellLiteral("exit 3"),
          )
        "Compare cold and warm candidate results" -> listOf(ShellLiteral("exit 3"))
        else -> error("Unknown comparison step: $stepName")
      }
    val expectedStatements =
      listOf<WorkflowStatement>(
        ShellLiteral(STRICT_MODE_LINE),
        ShellLiteral(DRIVER_ASSIGNMENT),
        ShellLiteral("set +e"),
        BenchmarkInvocation(
          "compare",
          expectedByMode.getValue("cold"),
          setOf("--enforce-release-gates"),
        ),
        ShellLiteral("cold_status=${'$'}?"),
        BenchmarkInvocation(
          "compare",
          expectedByMode.getValue("warm"),
          setOf("--enforce-release-gates"),
        ),
        ShellLiteral("warm_status=${'$'}?"),
        ShellLiteral("set -e"),
        ShellLiteral("if (( cold_status != 0 || warm_status != 0 )); then"),
      ) + failureBody + ShellLiteral("fi")

    assertThat(WorkflowShellParser.parse(script).statements)
      .containsExactlyElementsIn(expectedStatements)
      .inOrder()
  }

  private fun coldCampaignOptions(
    candidate: String,
    candidateAdapter: String,
    suffix: String,
  ): Map<String, String> =
    campaignOptions(
      mode = "cold",
      candidate = candidate,
      candidateAdapter = candidateAdapter,
      blocks = "50",
      warmups = "0",
      iterations = "1",
      metrics = "latency,peak-rss,allocation",
      suffix = suffix,
    )

  private fun warmCampaignOptions(
    candidate: String,
    candidateAdapter: String,
    suffix: String,
  ): Map<String, String> =
    campaignOptions(
      mode = "warm",
      candidate = candidate,
      candidateAdapter = candidateAdapter,
      blocks = "5",
      warmups = "20",
      iterations = "100",
      metrics = "latency,allocation",
      suffix = suffix,
    )

  private fun campaignOptions(
    mode: String,
    candidate: String,
    candidateAdapter: String,
    blocks: String,
    warmups: String,
    iterations: String,
    metrics: String,
    suffix: String,
  ): Map<String, String> =
    linkedMapOf(
      "--mode" to mode,
      "--intent" to "controlled",
      "--baseline" to "${'$'}RUN_ROOT/manifests/baseline-a.json",
      "--baseline-adapter" to "baseline-83f3cd70",
      "--candidate" to "${'$'}RUN_ROOT/manifests/$candidate.json",
      "--candidate-adapter" to candidateAdapter,
      "--workload" to "lifecycle.no-script-one-step.v1",
      "--blocks" to blocks,
      "--forks-per-block" to "1",
      "--warmups" to warmups,
      "--iterations" to iterations,
      "--seed" to "5928239383101656625",
      "--metrics" to metrics,
      "--host-policy" to "${'$'}HOST_POLICY_PATH",
      "--artifacts-dir" to "${'$'}RUN_ROOT/jfr/$mode-$suffix",
      "--output" to "${'$'}RUN_ROOT/results/$mode-$suffix.json",
    )

  private fun expectedComparisonOptions(
    prefix: String,
    inputSuffix: String,
  ): Map<String, Map<String, String>> =
    listOf("cold", "warm").associateWith { mode ->
      linkedMapOf(
        "--input" to "${'$'}RUN_ROOT/results/$mode-$inputSuffix.json",
        "--output-json" to "${'$'}RUN_ROOT/results/comparison-$prefix-$mode.json",
        "--output-md" to "${'$'}RUN_ROOT/results/comparison-$prefix-$mode.md",
      )
    }

  private fun stepByName(
    steps: List<Map<String, Any?>>,
    name: String,
  ): Map<String, Any?> = steps.single { it["name"] == name }

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

  private fun unverifiedControlledSteps(): List<Map<String, Any?>> =
    renderedControlledWorkflow().asMap("jobs").asMap("benchmark").asList("steps").map { it.asMap() }

  private fun controlledSteps(): List<Map<String, Any?>> =
    unverifiedControlledSteps().also(::assertRunScriptContracts)

  private fun bashRunScriptEntries(steps: List<Map<String, Any?>>): List<Pair<String, String>> =
    steps
      .filter { it["shell"] == "bash" }
      .map { step ->
        requireNotNull(step["name"] as? String) to requireNotNull(step["run"] as? String)
      }

  private fun assertRunScriptContracts(steps: List<Map<String, Any?>>) {
    val entries = bashRunScriptEntries(steps)
    assertWithMessage("controlled Bash step names")
      .that(RUN_SCRIPT_CONTRACTS.keys)
      .containsExactlyElementsIn(entries.map { it.first })
      .inOrder()
    val runScripts = entries.toMap()
    RUN_SCRIPT_CONTRACTS.forEach { (stepName, contractFile) ->
      val contract =
        Files.readAllBytes(
          repositoryRoot.resolve(RUN_SCRIPT_CONTRACT_DIRECTORY).resolve(contractFile)
        )
      val actual = runScripts.getValue(stepName).toByteArray(StandardCharsets.UTF_8)
      assertWithMessage("exact Bash run-script contract for $stepName")
        .that(actual)
        .isEqualTo(contract)
    }
  }

  private fun mutateRunScript(
    steps: List<Map<String, Any?>>,
    stepName: String,
    mutation: (String) -> String,
  ): List<Map<String, Any?>> = steps.map { step ->
    if (step["name"] == stepName) {
      val script = requireNotNull(step["run"] as? String)
      val mutated = mutation(script)
      require(mutated != script) { "Mutation did not change $stepName" }
      step + ("run" to mutated)
    } else {
      step
    }
  }

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
    val CANDIDATE_REF: String = "b".repeat(40)
    const val CANDIDATE_ADAPTER: String = "major-v1"
    const val HOST_POLICY_PATH: String = "/opt/revoman-benchmark/policies/policy with spaces.json"
    const val FIXED_BASELINE_COMMIT: String = "83f3cd70f78ad733412d10cbc8287aaabafe7aac"
    const val CHECKOUT_ACTION: String = "actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5"
    const val SETUP_JAVA_ACTION: String =
      "actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9"
    const val SETUP_GRADLE_ACTION: String =
      "gradle/actions/setup-gradle@ed408507eac070d1f99cc633dbcf757c94c7933a"
    const val UPLOAD_ARTIFACT_ACTION: String =
      "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02"
    const val STRICT_MODE_LINE: String = "set -euo pipefail"
    const val DRIVER_ASSIGNMENT: String =
      "DRIVER=\"${'$'}GITHUB_WORKSPACE/harness/benchmark-driver/build/install/" +
        "benchmark-driver/bin/benchmark-driver\""
    const val RUN_SCRIPT_CONTRACT_DIRECTORY: String =
      "src/test/resources/benchmark/workflow-scripts"
    val RUN_SCRIPT_CONTRACTS: Map<String, String> =
      linkedMapOf(
        "Validate requested refs" to "validate-requested-refs.sh",
        "Validate clean checkout identities" to "validate-clean-checkout-identities.sh",
        "Prepare unique run root" to "prepare-unique-run-root.sh",
        "Install benchmark driver" to "install-benchmark-driver.sh",
        "Export baseline-a target" to "export-baseline-a-target.sh",
        "Export baseline-b target" to "export-baseline-b-target.sh",
        "Export candidate target" to "export-candidate-target.sh",
        "Capture cold A-A" to "capture-cold-aa.sh",
        "Capture warm A-A" to "capture-warm-aa.sh",
        "Require cold and warm A-A gates" to "require-cold-and-warm-aa-gates.sh",
        "Capture cold candidate comparison" to "capture-cold-candidate-comparison.sh",
        "Capture warm candidate comparison" to "capture-warm-candidate-comparison.sh",
        "Compare cold and warm candidate results" to "compare-cold-and-warm-candidate-results.sh",
      )
    val CANDIDATE_MEASUREMENT_STEPS: List<String> =
      listOf(
        "Capture cold candidate comparison",
        "Capture warm candidate comparison",
        "Compare cold and warm candidate results",
      )
    val INPUT_EXPRESSION: Regex = Regex("\\$\\{\\{\\s*inputs\\.([a-z_]+)\\s*}}")
  }
}

private sealed interface WorkflowStatement

private data class ShellLiteral(val line: String) : WorkflowStatement

private data class BenchmarkInvocation(
  val command: String,
  val options: Map<String, String>,
  val flags: Set<String>,
) : WorkflowStatement

private data class ParsedWorkflowScript(val statements: List<WorkflowStatement>)

private object WorkflowShellParser {
  fun parse(script: String): ParsedWorkflowScript =
    ParsedWorkflowScript(logicalLines(script).map(::parseStatement))

  fun benchmarkInvocations(script: String): List<BenchmarkInvocation> =
    parse(script).statements.filterIsInstance<BenchmarkInvocation>()

  private fun logicalLines(script: String): List<String> {
    val lines = mutableListOf<String>()
    val current = StringBuilder()
    physicalLines(script).forEach { physicalLine ->
      require(physicalLine == physicalLine.trimEnd()) {
        "Shell physical line has trailing whitespace: <$physicalLine>"
      }
      val line = physicalLine.trimStart()
      require(line.isNotBlank() && !line.startsWith('#')) {
        "Shell command scripts cannot contain blank or comment-only lines: <$physicalLine>"
      }
      val continued = physicalLine.endsWith('\\')
      val segment = if (continued) line.dropLast(1).trimEnd() else line
      if (current.isNotEmpty()) current.append(' ')
      current.append(segment)
      if (!continued) {
        lines += current.toString()
        current.clear()
      }
    }
    require(current.isEmpty()) { "Dangling shell continuation: $current" }
    return lines
  }

  private fun physicalLines(script: String): List<String> =
    script.split('\n').let { lines ->
      if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
    }

  private fun parseStatement(line: String): WorkflowStatement {
    val tokenization = ShellTokenizer(line).tokenize()
    require(!tokenization.commentSeen) { "Shell command scripts cannot contain comments: $line" }
    val tokens = tokenization.tokens
    if (line.startsWith("DRIVER=")) {
      require(line == DRIVER_ASSIGNMENT) { "Unexpected DRIVER assignment: $line" }
    }
    return if (tokens.firstOrNull() == "${'$'}DRIVER") {
      parseBenchmarkInvocation(tokens)
    } else {
      ShellLiteral(line)
    }
  }

  private fun parseBenchmarkInvocation(tokens: List<String>): BenchmarkInvocation {
    require(tokens.none { it in SHELL_CONTROL_OPERATORS }) {
      "Benchmark invocation cannot contain shell control operators: $tokens"
    }
    require(tokens.size >= 2) { "Benchmark invocation has no command: $tokens" }
    val options = linkedMapOf<String, String>()
    val flags = linkedSetOf<String>()
    var index = 2
    while (index < tokens.size) {
      val option = tokens[index]
      require(option.startsWith("--")) { "Unexpected benchmark argv token: $option" }
      if (option in VALUELESS_FLAGS) {
        require(flags.add(option)) { "Duplicate benchmark flag: $option" }
        index += 1
      } else {
        require(index + 1 < tokens.size && !tokens[index + 1].startsWith("--")) {
          "Benchmark option needs a value: $option"
        }
        require(options.put(option, tokens[index + 1]) == null) {
          "Duplicate benchmark option: $option"
        }
        index += 2
      }
    }
    return BenchmarkInvocation(command = tokens[1], options = options, flags = flags)
  }

  private const val DRIVER_ASSIGNMENT: String =
    "DRIVER=\"${'$'}GITHUB_WORKSPACE/harness/benchmark-driver/build/install/" +
      "benchmark-driver/bin/benchmark-driver\""
  private val SHELL_CONTROL_OPERATORS: Set<String> = setOf(";", "&", "|")
  private val VALUELESS_FLAGS: Set<String> = setOf("--enforce-release-gates")
}

private data class ShellTokenization(val tokens: List<String>, val commentSeen: Boolean)

private class ShellTokenizer(private val line: String) {
  private val tokens = mutableListOf<String>()
  private val token = StringBuilder()
  private var quote: Char? = null
  private var escaped = false
  private var commentSeen = false

  fun tokenize(): ShellTokenization {
    for (character in line) {
      if (consume(character)) break
    }
    require(quote == null && !escaped) { "Unterminated shell token in: $line" }
    flushToken()
    return ShellTokenization(tokens = tokens, commentSeen = commentSeen)
  }

  private fun consume(character: Char): Boolean =
    when {
      escaped -> consumeEscaped(character)
      quote != null -> consumeQuoted(character)
      else -> consumeUnquoted(character)
    }

  private fun consumeEscaped(character: Char): Boolean {
    token.append(character)
    escaped = false
    return false
  }

  private fun consumeQuoted(character: Char): Boolean {
    when {
      character == quote -> quote = null
      character == '\\' && quote != '\'' -> escaped = true
      else -> token.append(character)
    }
    return false
  }

  private fun consumeUnquoted(character: Char): Boolean =
    when {
      character == '\\' -> beginEscape()
      character in QUOTE_CHARACTERS -> beginQuote(character)
      character.isWhitespace() -> consumeWhitespace()
      character == '#' && token.isEmpty() -> beginComment()
      character in CONTROL_CHARACTERS -> consumeControl(character)
      else -> consumeLiteral(character)
    }

  private fun beginEscape(): Boolean = false.also { escaped = true }

  private fun beginQuote(character: Char): Boolean = false.also { quote = character }

  private fun consumeWhitespace(): Boolean = false.also { flushToken() }

  private fun beginComment(): Boolean = true.also { commentSeen = true }

  private fun consumeControl(character: Char): Boolean =
    false.also {
      flushToken()
      tokens += character.toString()
    }

  private fun consumeLiteral(character: Char): Boolean = false.also { token.append(character) }

  private fun flushToken() {
    if (token.isNotEmpty()) {
      tokens += token.toString()
      token.clear()
    }
  }

  private companion object {
    val QUOTE_CHARACTERS: Set<Char> = setOf('\'', '"')
    val CONTROL_CHARACTERS: Set<Char> = setOf(';', '&', '|')
  }
}
