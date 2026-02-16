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
  }
}

dependencyResolutionManagement {
  versionCatalogs { 
    create("ktorLibs") { from("io.ktor:ktor-version-catalog:3.4.0") }
  }
  repositories {
    maven("https://packages.jetbrains.team/maven/p/grazi/grazie-platform-public") {
      mavenContent {
        includeGroup("ai.koog")
      }
    }
    mavenCentral()
  }
}

plugins { id("com.gradle.develocity") version "4.3.2" }

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

includeBuild("../../.") {
  name = "koog"
}

rootProject.name = "revoman-root"
