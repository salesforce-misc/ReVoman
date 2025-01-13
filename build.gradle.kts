/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
plugins {
  id("revoman.root-conventions")
  id("revoman.publishing-conventions")
  id("revoman.kt-conventions")
  alias(libs.plugins.moshix)
  alias(libs.plugins.node.gradle)
  alias(libs.plugins.kover)
  alias(libs.plugins.nexus.publish)
  alias(libs.plugins.gradle.taskinfo)
}

dependencies {
  api(libs.bundles.http4k)
  api(libs.moshix.adapters)
  api(libs.java.vavr)
  api(libs.kotlin.vavr)
  api(libs.arrow.core)
  api(libs.kotlinx.datetime)
  implementation(libs.bundles.kotlin.logging)
  implementation(libs.pprint)
  implementation(libs.graal.js)
  implementation(libs.kotlin.faker)
  implementation(libs.underscore)
  implementation(libs.okio.jvm)
  implementation(libs.spring.beans)
  kapt(libs.immutables.value)
  compileOnly(libs.immutables.builder)
  compileOnly(libs.immutables.value.annotations)
  compileOnly(libs.jetbrains.annotations)
  testImplementation(libs.truth)
  testImplementation(libs.json.assert)
  testImplementation(libs.mockk)
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) { useJUnitJupiter(libs.versions.junit.get()) }
    val integrationTest by
      registering(JvmTestSuite::class) {
        dependencies {
          implementation(project())
          implementation(libs.truth)
          implementation(libs.mockito.core)
          implementation(libs.spring.beans)
          implementation(libs.json.assert)
        }
      }
  }
}

node { nodeProjectDir = file("${project.projectDir}/js") }

tasks {
  check { dependsOn(npmInstall) }
  test { dependsOn(npmInstall) }
}

// kover { reports { total { html { onCheck = true } } } }

moshi { enableSealed = true }

nexusPublishing { this.repositories { sonatype { stagingProfileId = STAGING_PROFILE_ID } } }
