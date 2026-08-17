/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
plugins {
  kotlin("jvm")
  application
}

repositories { mavenCentral() }

dependencies {
  implementation(libs.json.schema.validator)
  implementation(libs.moshi)
  testImplementation(libs.bundles.kotest)
}

kotlin { jvmToolchain(libs.versions.jdk.get().toInt()) }

application { mainClass.set("performance.cli.PerformanceRunnerMainKt") }

val installDistLib = tasks.installDist.map { install -> install.destinationDir.resolve("lib") }

tasks.test {
  useJUnitPlatform()
  dependsOn(tasks.installDist)
  inputs.dir(installDistLib)
  systemProperty(
    "performance.runner.install-dist-lib",
    installDistLib.get().absolutePath,
  )
}
