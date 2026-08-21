/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.ci

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.NodeId
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver

internal typealias YamlMap = Map<String, Any?>

internal const val CHECKOUT_ACTION =
  "actions/checkout@11d5960a326750d5838078e36cf38b85af677262"
internal const val SETUP_JAVA_ACTION =
  "actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3"
internal const val SETUP_GRADLE_ACTION =
  "gradle/actions/setup-gradle@ed408507eac070d1f99cc633dbcf757c94c7933a"
internal const val UPLOAD_ARTIFACT_ACTION =
  "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02"
internal const val UPLOAD_SARIF_ACTION =
  "github/codeql-action/upload-sarif@f3712979fa5f215279b101dd0a2e3bdfb4353324"
internal const val QODANA_ACTION =
  "JetBrains/qodana-action@4861e015da555e86a72b862892aba6c2b93e6891"

private val approvedActionReferences =
  setOf(
    CHECKOUT_ACTION,
    SETUP_JAVA_ACTION,
    SETUP_GRADLE_ACTION,
    UPLOAD_ARTIFACT_ACTION,
    UPLOAD_SARIF_ACTION,
    QODANA_ACTION,
  )

class WorkflowSecurityContractTest :
  FunSpec(
    {
      test("the workflow parser rejects duplicate keys and excessive aliases and preserves on") {
        shouldThrow<YAMLException> {
          parseYaml(
            """
            jobs: {}
            jobs: {}
            """
              .trimIndent()
          )
        }

        val excessiveAliases =
          buildString {
            appendLine("value: &value [safe]")
            appendLine("aliases:")
            repeat(9) { appendLine("  - *value") }
          }
        shouldThrow<YAMLException> { parseYaml(excessiveAliases) }

        parseYaml("on:\n  workflow_dispatch:\n").keys shouldBe setOf("on")
      }

      test("automatic CI stays an ARM structural lane with immutable least-privilege inputs") {
        val workflow = loadYaml(".github/workflows/build.yml")
        val events = workflow.requiredMap("on")
        events.keys shouldContainExactlyInAnyOrder setOf("push", "pull_request")
        events.requiredMap("push").requiredList("branches") shouldBe listOf("master")
        events.requiredMap("pull_request").requiredList("branches") shouldBe listOf("master")
        workflow.requiredMap("permissions") shouldContainExactly mapOf("contents" to "read")

        val jobs = workflow.requiredMap("jobs")
        jobs.keys shouldBe setOf("gradle")
        val job = jobs.requiredMap("gradle")
        job.requiredString("runs-on") shouldBe "ubuntu-24.04-arm"
        job.requiredInt("timeout-minutes") shouldBe 90

        val steps = job.requiredSteps()
        val checkout = steps.singleUsing(CHECKOUT_ACTION)
        checkout.requiredMap("with").requiredBoolean("persist-credentials") shouldBe false
        steps.singleUsing(SETUP_GRADLE_ACTION).requiredMap("with").requiredString(
          "cache-read-only"
        ) shouldBe "${'$'}{{ github.event_name == 'pull_request' }}"

        steps.named("Build build logic").requiredString("run") shouldBe
          "./gradlew -p buildSrc test"
        steps.named("Build project").requiredString("run") shouldBe "./gradlew build"
        normalizeShell(steps.named("Freeze performance distribution").requiredString("run")) shouldBe
          "./scripts/performance/run freeze --treatment-source . " +
            "--output build/performance/distribution"
        normalizeShell(steps.named("Run structural performance canary").requiredString("run")) shouldBe
          "./scripts/performance/run canary " +
            "--distribution build/performance/distribution " +
            "--host-id github-hosted-arm64-canary-v1 " +
            "--output build/performance/canary"

        val upload = steps.named("Upload sanitized performance diagnostics")
        upload.requiredString("uses") shouldBe UPLOAD_ARTIFACT_ACTION
        upload.requiredString("if") shouldBe "always()"
        upload.requiredMap("with") shouldContainExactly
          mapOf(
            "name" to "performance-canary-diagnostics",
            "path" to "build/performance",
            "if-no-files-found" to "error",
            "retention-days" to 7,
          )

        assertApprovedActions(workflow)
        assertSecretless(workflow)
        assertPerformancePolicyIsNotDuplicated(workflow)
      }

      test("manual hosted performance runs exactly one trusted diagnostic canary") {
        val workflow = loadYaml(".github/workflows/performance-campaign.yml")
        val events = workflow.requiredMap("on")
        events.keys shouldBe setOf("workflow_dispatch")
        val inputs = events.requiredMap("workflow_dispatch").requiredMap("inputs")
        inputs.keys shouldContainExactlyInAnyOrder setOf("baseline_sha", "candidate_sha")
        listOf("baseline_sha", "candidate_sha").forEach { inputName ->
          inputs.requiredMap(inputName).apply {
            requiredBoolean("required") shouldBe true
            requiredString("type") shouldBe "string"
          }
        }
        workflow.requiredMap("permissions") shouldContainExactly mapOf("contents" to "read")

        val jobs = workflow.requiredMap("jobs")
        jobs.keys shouldBe setOf("diagnostic")
        val job = jobs.requiredMap("diagnostic")
        job.requiredString("runs-on") shouldBe "ubuntu-24.04-arm"
        job.requiredInt("timeout-minutes") shouldBe 240
        job.containsKey("if") shouldBe false

        val steps = job.requiredSteps()
        val first = steps.first()
        first.requiredString("name") shouldBe "Require trusted master ref"
        first.requiredString("run") shouldBe "test \"${'$'}GITHUB_REF\" = \"refs/heads/master\""

        val checkout = steps.singleUsing(CHECKOUT_ACTION)
        checkout.requiredMap("with") shouldContainExactly
          mapOf(
            "ref" to "refs/heads/master",
            "fetch-depth" to 0,
            "persist-credentials" to false,
          )

        val trustedMaster = steps.named("Fetch and assert trusted master")
        normalizeShell(trustedMaster.requiredString("run")).apply {
          this shouldContain "+refs/heads/*:refs/remotes/origin/*"
          this shouldContain "+refs/tags/*:refs/tags/*"
          this shouldContain "git rev-parse refs/remotes/origin/master"
        }

        val canary = steps.named("Validate inputs and run diagnostic canary")
        canary.requiredMap("env") shouldContainExactly
          mapOf(
            "BASELINE_SHA" to "${'$'}{{ inputs.baseline_sha }}",
            "CANDIDATE_SHA" to "${'$'}{{ inputs.candidate_sha }}",
          )
        val script = normalizeShell(canary.requiredString("run"))
        assertOwnedCommitReachability(script)
        listOf(
            "[[ \"${'$'}BASELINE_SHA\" =~ ^[0-9a-f]{40}${'$'} ]]",
            "[[ \"${'$'}CANDIDATE_SHA\" =~ ^[0-9a-f]{40}${'$'} ]]",
            "[[ \"${'$'}BASELINE_SHA\" != \"${'$'}CANDIDATE_SHA\" ]]",
            "git cat-file -e \"${'$'}commit^{commit}\"",
            "refs/remotes/origin refs/tags",
            "env -u GITHUB_TOKEN -u GH_TOKEN -u ACTIONS_ID_TOKEN_REQUEST_TOKEN",
            "./scripts/performance/run freeze --treatment-source \"${'$'}baseline_source\" " +
              "--output build/performance/baseline",
            "./scripts/performance/run freeze --treatment-source \"${'$'}candidate_source\" " +
              "--harness-from build/performance/baseline " +
              "--output build/performance/candidate",
            "./scripts/performance/run canary " +
              "--host-id github-hosted-arm64-canary-v1 " +
              "--distribution build/performance/candidate " +
              "--output build/performance/canary",
          )
          .forEach(script::shouldContain)
        Regex("\\./scripts/performance/run").findAll(script).count() shouldBe 3
        Regex("\\./scripts/performance/run canary").findAll(script).count() shouldBe 1
        script shouldNotContain "./scripts/performance/run campaign"
        script shouldNotContain "./scripts/performance/run compare"
        script shouldContain "git worktree add --detach"
        script.contains("refs/pull") shouldBe false

        val upload = steps.named("Upload hosted canary diagnostic evidence")
        upload.requiredString("uses") shouldBe UPLOAD_ARTIFACT_ACTION
        upload.requiredString("if") shouldBe "always()"
        upload.requiredMap("with") shouldContainExactly
          mapOf(
            "name" to "hosted-performance-canary-diagnostic",
            "path" to "build/performance",
            "if-no-files-found" to "error",
            "retention-days" to 30,
          )

        assertApprovedActions(workflow)
        assertSecretless(workflow)
        assertPerformancePolicyIsNotDuplicated(workflow)
        workflow.scalarStrings().any { it.contains("pull_request_target") } shouldBe false
        workflow.scalarStrings().any { it.contains("schedule") } shouldBe false
        workflow.scalarStrings().any { it.contains("actions/download-artifact") } shouldBe false
        workflow.scalarStrings().any { it.contains("latest", ignoreCase = true) } shouldBe false
      }

      test("manual hosted performance rejects reachability and job credential mutations") {
        val source = Files.readString(repositoryPath(".github/workflows/performance-campaign.yml"))
        val reachabilityBypass =
          source.replace(
            "            reachable_from_owned_ref \"\$commit\"\n",
            "",
          )
        (reachabilityBypass == source) shouldBe false
        shouldThrow<AssertionError> {
          val mutated = parseYaml(reachabilityBypass)
          val script =
            normalizeShell(
              mutated
                .requiredMap("jobs")
                .requiredMap("diagnostic")
                .requiredSteps()
                .named("Validate inputs and run diagnostic canary")
                .requiredString("run"),
            )
          assertOwnedCommitReachability(script)
        }

        mapOf(
            "id-token" to "write",
            "contents" to "write",
          )
          .forEach { (permission, access) ->
            val mutated =
              source.replace(
                "  diagnostic:\n    runs-on:",
                "  diagnostic:\n    permissions:\n      $permission: $access\n    runs-on:",
              )
            (mutated == source) shouldBe false
            shouldThrow<AssertionError> { assertSecretless(parseYaml(mutated)) }
          }
      }
    }
  )

internal fun repositoryPath(relativePath: String): Path =
  generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
    .firstOrNull { Files.isDirectory(it.resolve("buildSrc")) && Files.exists(it.resolve(".git")) }
    ?.resolve(relativePath)
    ?: error("Cannot locate repository root from ${Path.of("").toAbsolutePath()}")

internal fun loadYaml(relativePath: String): YamlMap =
  parseYaml(Files.readString(repositoryPath(relativePath)))

internal fun parseYaml(source: String): YamlMap {
  val loaderOptions =
    LoaderOptions().apply {
      isAllowDuplicateKeys = false
      maxAliasesForCollections = 8
      nestingDepthLimit = 40
      codePointLimit = 1_000_000
    }
  val dumperOptions = DumperOptions()
  val yaml =
    Yaml(
      SafeConstructor(loaderOptions),
      Representer(dumperOptions),
      dumperOptions,
      loaderOptions,
      GithubActionsResolver(),
    )
  return yaml.load<Any?>(source).requiredYamlMap("YAML document")
}

private class GithubActionsResolver : Resolver() {
  override fun resolve(kind: NodeId, value: String?, implicit: Boolean): Tag =
    if (kind == NodeId.scalar && implicit && value == "on") Tag.STR
    else super.resolve(kind, value, implicit)
}

internal fun Any?.requiredYamlMap(context: String): YamlMap =
  (this as? Map<*, *>)
    ?.entries
    ?.associate { (key, value) ->
      val stringKey = key as? String ?: error("$context contains non-string key: $key")
      stringKey to value
    } ?: error("$context must be a mapping, found ${this?.javaClass?.name}")

internal fun YamlMap.requiredMap(key: String): YamlMap =
  getValue(key).requiredYamlMap("$key mapping")

internal fun YamlMap.requiredList(key: String): List<Any?> =
  getValue(key) as? List<Any?> ?: error("$key must be a list")

internal fun YamlMap.requiredString(key: String): String =
  getValue(key) as? String ?: error("$key must be a string")

internal fun YamlMap.requiredBoolean(key: String): Boolean =
  getValue(key) as? Boolean ?: error("$key must be a boolean")

internal fun YamlMap.requiredInt(key: String): Int =
  getValue(key) as? Int ?: error("$key must be an integer")

internal fun YamlMap.requiredSteps(): List<YamlMap> =
  requiredList("steps").mapIndexed { index, step -> step.requiredYamlMap("step $index") }

internal fun List<YamlMap>.named(name: String): YamlMap =
  single { it["name"] == name }

internal fun List<YamlMap>.singleUsing(action: String): YamlMap =
  single { it["uses"] == action }

internal fun Any?.scalarStrings(): Sequence<String> =
  when (this) {
    is Map<*, *> ->
      entries.asSequence().flatMap { (key, value) -> sequenceOf(key.toString()) + value.scalarStrings() }
    is Iterable<*> -> asSequence().flatMap { it.scalarStrings() }
    is String -> sequenceOf(this)
    else -> emptySequence()
  }

internal fun normalizeShell(script: String): String =
  script
    .replace("\\\n", " ")
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .joinToString(" ")
    .replace(Regex("\\s+"), " ")

internal fun assertApprovedActions(workflow: YamlMap) {
  val actionReferences =
    workflow.requiredMap("jobs").values.flatMap { job ->
      job.requiredYamlMap("job").requiredSteps().mapNotNull { it["uses"] as? String }
    }
  actionReferences.all { it.matches(Regex("^[^@]+@[0-9a-f]{40}${'$'}")) } shouldBe true
  actionReferences.all(approvedActionReferences::contains) shouldBe true
}

internal fun assertSecretless(workflow: YamlMap) {
  workflow.scalarStrings().any { it.contains("secrets.", ignoreCase = true) } shouldBe false
  workflow.requiredMap("permissions").containsKey("id-token") shouldBe false
  workflow.requiredMap("jobs").values.forEach { value ->
    val job = value.requiredYamlMap("job")
    val permissions = job["permissions"]?.requiredYamlMap("job permissions").orEmpty()
    permissions.containsKey("id-token") shouldBe false
    permissions.values.any { it == "write" } shouldBe false
  }
}

private fun assertOwnedCommitReachability(script: String) {
  script shouldContain "reachable_from_owned_ref \"${'$'}commit\""
}

private fun assertPerformancePolicyIsNotDuplicated(workflow: YamlMap) {
  val strings = workflow.scalarStrings().toList()
  listOf(
      "docker run",
      "--network",
      "--pull",
      "--cpus",
      "--memory",
      "--memory-swap",
      "--pids-limit",
      "--cap-drop",
      "--security-opt",
      "--read-only",
      "--regression-policy",
      "threshold",
      "score",
    )
    .forEach { forbidden ->
      strings.any { it.contains(forbidden, ignoreCase = true) } shouldBe false
    }
}
