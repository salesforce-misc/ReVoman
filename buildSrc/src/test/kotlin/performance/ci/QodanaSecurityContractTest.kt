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

private const val QODANA_INDEX =
  "jetbrains/qodana-jvm-community@sha256:" +
    "f1c5d3efe2f550409c4d95d266c5dc2025a8069d82c9516781eae72e7383b55d"
private const val QODANA_AMD64_CHILD =
  "sha256:6e03fbb417f0f268333ae04d97c4221bdb6bb666a30f0de8b4a34c521e797622"
private const val QODANA_ARM64_CHILD =
  "sha256:a8ea6d25700098433060c62818b6172a3ebbf409573353cb7b35b76b07093870"

class QodanaSecurityContractTest :
  FunSpec(
    {
      test("Qodana PR analysis is secretless read-only and publishes only a pinned artifact") {
        val workflow = loadYaml(".github/workflows/qodana.yml")
        val events = workflow.requiredMap("on")
        events.keys shouldContainExactlyInAnyOrder setOf("push", "pull_request")
        events.requiredMap("push").requiredList("branches") shouldBe listOf("master")
        events.requiredMap("pull_request").requiredList("branches") shouldBe listOf("master")
        workflow.requiredMap("permissions") shouldBe emptyMap()

        val jobs = workflow.requiredMap("jobs")
        jobs.keys shouldContainExactlyInAnyOrder setOf("pull-request", "trusted-master")
        val job = jobs.requiredMap("pull-request")
        job.requiredString("if") shouldBe "${'$'}{{ github.event_name == 'pull_request' }}"
        job.requiredString("runs-on") shouldBe "ubuntu-24.04"
        job.requiredMap("permissions") shouldContainExactly mapOf("contents" to "read")

        val steps = job.requiredSteps()
        assertCredentiallessCheckout(steps)
        assertQodanaImageVerificationPrecedesScan(steps)
        val scan = steps.named("Qodana PR scan")
        scan.requiredString("uses") shouldBe QODANA_ACTION
        assertLockedDownQodanaInputs(scan.requiredMap("with"), expectedPrMode = true)
        scan.containsKey("env") shouldBe false
        job.scalarStrings().any { it.contains("secrets.", ignoreCase = true) } shouldBe false

        val publishingActions =
          steps.mapNotNull { it["uses"] as? String }.filter {
            it == UPLOAD_ARTIFACT_ACTION || it == UPLOAD_SARIF_ACTION
          }
        publishingActions shouldBe listOf(UPLOAD_ARTIFACT_ACTION)
        steps.singleUsing(UPLOAD_ARTIFACT_ACTION).apply {
          requiredString("if") shouldBe "always()"
          requiredMap("with") shouldContainExactly
            mapOf(
              "name" to "qodana-pr-report",
              "path" to "${'$'}{{ runner.temp }}/qodana/results",
              "if-no-files-found" to "error",
              "retention-days" to 7,
            )
        }

        assertApprovedActions(workflow)
        workflow.scalarStrings().any { it.contains("pull_request_target") } shouldBe false
      }

      test("trusted master Qodana scopes its token and SARIF permission to the trusted job") {
        val workflow = loadYaml(".github/workflows/qodana.yml")
        val job = workflow.requiredMap("jobs").requiredMap("trusted-master")
        job.requiredString("if") shouldBe
          "${'$'}{{ github.event_name == 'push' && github.ref == 'refs/heads/master' }}"
        job.requiredString("runs-on") shouldBe "ubuntu-24.04"
        job.requiredMap("permissions") shouldContainExactly
          mapOf("contents" to "read", "security-events" to "write")

        val steps = job.requiredSteps()
        assertCredentiallessCheckout(steps)
        assertQodanaImageVerificationPrecedesScan(steps)
        val scan = steps.named("Qodana trusted master scan")
        scan.requiredString("uses") shouldBe QODANA_ACTION
        assertLockedDownQodanaInputs(scan.requiredMap("with"), expectedPrMode = false)
        scan.requiredMap("env") shouldContainExactly
          mapOf("QODANA_TOKEN" to "${'$'}{{ secrets.QODANA_TOKEN }}")

        val secretValues = workflow.scalarStrings().filter { it.contains("secrets.") }.toList()
        secretValues shouldBe listOf("${'$'}{{ secrets.QODANA_TOKEN }}")
        val sarif = steps.singleUsing(UPLOAD_SARIF_ACTION)
        sarif.requiredString("if") shouldBe "always()"
        sarif.requiredMap("with").requiredString("sarif_file") shouldBe
          "${'$'}{{ runner.temp }}/qodana/results/qodana.sarif.json"

        assertApprovedActions(workflow)
      }

      test("Qodana image and platform children are immutable before either scan") {
        val qodana = loadYaml("qodana.yaml")
        qodana.requiredString("version") shouldBe "1.0"
        qodana.requiredString("projectJDK") shouldBe "21"
        qodana.requiredString("linter") shouldBe QODANA_INDEX

        val workflow = loadYaml(".github/workflows/qodana.yml")
        workflow.requiredMap("jobs").values.forEach { value ->
          val steps = value.requiredYamlMap("Qodana job").requiredSteps()
          val verification = steps.named("Verify immutable Qodana image")
          assertQodanaPlatformSelection(normalizeShell(verification.requiredString("run")))
        }
      }

      test("Qodana platform verification rejects selected-child equality mutations") {
        val source = java.nio.file.Files.readString(repositoryPath(".github/workflows/qodana.yml"))
        mapOf(
            "removed" to "test -n \"${'$'}actual\"",
            "inverted" to "test \"${'$'}actual\" != \"${'$'}expected\"",
          )
          .forEach { (_, replacement) ->
            val mutated = source.replace("test \"${'$'}actual\" = \"${'$'}expected\"", replacement)
            (mutated == source) shouldBe false
            parseYaml(mutated).requiredMap("jobs").values.forEach { value ->
              val verification =
                value
                  .requiredYamlMap("Qodana job")
                  .requiredSteps()
                  .named("Verify immutable Qodana image")
              shouldThrow<AssertionError> {
                assertQodanaPlatformSelection(normalizeShell(verification.requiredString("run")))
              }
            }
          }
      }
    }
  )

private fun assertCredentiallessCheckout(steps: List<YamlMap>) {
  steps.singleUsing(CHECKOUT_ACTION).requiredMap("with").apply {
    requiredInt("fetch-depth") shouldBe 0
    requiredBoolean("persist-credentials") shouldBe false
  }
}

private fun assertQodanaImageVerificationPrecedesScan(steps: List<YamlMap>) {
  val verificationIndex = steps.indexOfFirst { it["name"] == "Verify immutable Qodana image" }
  val scanIndex = steps.indexOfFirst { it["uses"] == QODANA_ACTION }
  (verificationIndex >= 0) shouldBe true
  (scanIndex > verificationIndex) shouldBe true
}

private fun assertQodanaPlatformSelection(script: String) {
  script shouldContain QODANA_INDEX
  script shouldContain QODANA_AMD64_CHILD
  script shouldContain QODANA_ARM64_CHILD
  script shouldContain "docker buildx imagetools inspect"
  script shouldContain "--raw"
  script shouldContain "test \"${'$'}actual\" = \"${'$'}expected\""
}

private fun assertLockedDownQodanaInputs(inputs: YamlMap, expectedPrMode: Boolean) {
  inputs shouldContainExactly
    mapOf(
      "use-caches" to false,
      "use-annotations" to false,
      "pr-mode" to expectedPrMode,
      "post-pr-comment" to false,
      "github-token" to "",
      "push-fixes" to "none",
      "upload-result" to false,
    )
}
