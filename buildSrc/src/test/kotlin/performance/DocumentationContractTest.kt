/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import performance.ci.repositoryPath

private const val PINNED_JDK =
  "/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn"

class DocumentationContractTest :
  FunSpec(
    {
      test("local Qodana guidance uses Docker Desktop and the pinned JDK without a second VM") {
        val development = Files.readString(repositoryPath("DEVELOPMENT.md"))
        val build = Files.readString(repositoryPath("build.gradle.kts"))

        development.lowercase().contains("colima") shouldBe false
        build.lowercase().contains("colima") shouldBe false
        development shouldContain "docker --context desktop-linux info"
        development shouldContain "-Dorg.gradle.java.home=$PINNED_JDK"
        development shouldContain "./gradlew -q kaptKotlin classes"
        development shouldContain "./gradlew -q qodanaScan"
        development shouldContain "DOCKER_CONTEXT=desktop-linux"
      }

      test("performance documentation separates structural diagnostics from controlled-Mac claims") {
        val development = Files.readString(repositoryPath("DEVELOPMENT.md"))

        development shouldContain "ubuntu-24.04-arm"
        development shouldContain "structural canary"
        development shouldContain "workflow_dispatch"
        development shouldContain "diagnostic"
        development shouldContain "controlled Mac"
        development shouldContain "claim-bearing"
        development shouldContain "Docker, Git, and standard macOS utilities"
        development shouldContain "no host-native JVM"
        development shouldContain "no second VM"
        development shouldContain "no password"
        development shouldContain "no privilege escalation"
        development shouldContain "no persistent self-hosted runner"
        development shouldContain "no polling daemon"
      }
    }
  )
