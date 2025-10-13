/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
plugins { id("com.gradle.develocity") version "4.2.2" }

dependencyResolutionManagement {
  versionCatalogs { create("libs") { from(files("libs.versions.toml")) } }
  pluginManagement {
    repositories {
      mavenCentral()
      gradlePluginPortal()
      google()
      maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
      maven("https://oss.sonatype.org/content/repositories/snapshots")
    }
  }
}

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
