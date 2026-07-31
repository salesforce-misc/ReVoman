/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
plugins { id("revoman.kt-conventions") }

dependencies {
  // The deterministic execution engine. `project(":")` is the ReVoman library module.
  implementation(project(":"))

  implementation(libs.snakeyaml)

  // Truth for assertions, matching ReVoman's own revUp tests (RestfulAPIDevKtTest).
  testImplementation(libs.truth)
}

testing {
  suites {
    getByName<JvmTestSuite>("test") { useJUnitJupiter(libs.versions.junit.get()) }
  }
}

tasks.register<JavaExec>("runStage1Demo") {
  group = "harness"
  description = "Boot the mock CPQ server and run the configure->price->quote graph chain"
  mainClass.set("com.salesforce.revoman.harness.Stage1DemoKt")
  classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runStage2Demo") {
  group = "harness"
  description = "Run the orchestrator-workers loop (route -> slot-fill -> revUp) with the stub LLM"
  mainClass.set("com.salesforce.revoman.harness.Stage2DemoKt")
  classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runStage3Demo") {
  group = "harness"
  description = "Run the evals + calibration demo (confusion matrix, BFCL, tau-bench, LLM-judge)"
  mainClass.set("com.salesforce.revoman.harness.Stage3DemoKt")
  classpath = sourceSets["main"].runtimeClasspath
}

// --- Isolated `claude` source set: the ONLY place koog lives -------------------------------------
// Quarantines koog (a large Kotlin-multiplatform dependency built against a different Kotlin
// version) so a resolution/compat problem can never break the default `test`/`build`/`check`
// tasks, which never compile this source set. `compileClaudeKotlin` / `claudeDemo` are opt-in.
val claude: SourceSet by sourceSets.creating {
  compileClasspath += sourceSets["main"].output
  runtimeClasspath += sourceSets["main"].output
}

dependencies {
  "claudeImplementation"("ai.koog:koog-agents:1.1.1")
  "claudeImplementation"("ai.koog:koog-agents-additions:1.1.1-beta")
}

tasks.register<JavaExec>("claudeDemo") {
  group = "harness"
  description = "Run the orchestrator with the REAL Claude LLM (requires ANTHROPIC_API_KEY)"
  mainClass.set("com.salesforce.revoman.harness.ClaudeDemoKt")
  classpath = claude.runtimeClasspath
}
