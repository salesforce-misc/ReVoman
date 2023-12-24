/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ****************************************************************************
 */
plugins { id("com.gradle.enterprise") version "3.16.1" }

dependencyResolutionManagement {
  versionCatalogs { create("libs") { from(files("libs.versions.toml")) } }
  pluginManagement {
    repositories {
      mavenCentral()
      gradlePluginPortal()
      google()
      maven("https://oss.sonatype.org/content/repositories/snapshots")
    }
  }
}

gradleEnterprise {
  if (System.getenv("CI") != null) {
    buildScan {
      publishAlways()
      termsOfServiceUrl = "https://gradle.com/terms-of-service"
      termsOfServiceAgree = "yes"
    }
  }
}

rootProject.name = "revoman-root"
