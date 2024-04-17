/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
plugins { id("com.gradle.develocity") version "3.17.2" }

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

gradleEnterprise {
  buildScan {
    publishAlways()
    termsOfServiceUrl = "https://gradle.com/help/legal-terms-of-use"
    termsOfServiceAgree = "yes"
  }
}

rootProject.name = "revoman-root"
