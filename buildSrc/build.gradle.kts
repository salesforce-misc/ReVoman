/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
plugins { `kotlin-dsl` }

repositories {
  mavenCentral()
  gradlePluginPortal()
  maven("https://oss.sonatype.org/content/repositories/snapshots")
  // Workspace fallback: resolve the kotlin-dsl plugin deps from the internal Nexus mirror when
  // plugins.gradle.org is unreachable behind the SFDC proxy. Fully driven by the nexus* Gradle
  // properties (URL + credentials), so it is a no-op on CI / other machines (nothing checked in).
  val nexusUrl: String? = providers.gradleProperty("nexusGradlePluginsUrl").orNull
  val nexusUser: String? = providers.gradleProperty("nexusUsername").orNull
  val nexusPass: String? = providers.gradleProperty("nexusPassword").orNull
  if (nexusUrl != null && nexusUser != null && nexusPass != null) {
    maven {
      name = "nexusGradlePlugins"
      url = uri(nexusUrl)
      credentials {
        username = nexusUser
        password = nexusPass
      }
    }
  }
}

dependencies {
  implementation(libs.kotlin.gradle)
  implementation(libs.spotless.gradle)
  implementation(libs.detekt.gradle)
  implementation(libs.testLogger.gradle)
}
