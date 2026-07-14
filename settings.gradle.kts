/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    // Workspace fallback: plugins.gradle.org is unreachable behind the SFDC proxy, so resolve
    // Gradle plugins from the internal Nexus mirror instead. Fully driven by the nexus* Gradle
    // properties in ~/.gradle/gradle.properties (URL + credentials) — set only in the workspace,
    // absent on CI / other machines, so this repo is simply not added there (no SFDC-internal URL
    // or secrets checked in, no behavior change elsewhere).
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
}

dependencyResolutionManagement { repositories { mavenCentral() } }

plugins { id("com.gradle.develocity") version "4.5.0" }

val isCI = !System.getenv("CI").isNullOrEmpty()

develocity {
  buildScan {
    publishing.onlyIf {
      it.buildResult.failures.isNotEmpty() && !System.getenv("CI").isNullOrEmpty()
    }
    uploadInBackground.set(!isCI)
    termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
    termsOfUseAgree = "yes"
  }
}

rootProject.name = "revoman-root"
