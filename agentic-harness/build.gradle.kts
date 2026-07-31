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
