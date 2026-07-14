/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
dependencyResolutionManagement {
  versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }

  pluginManagement {
    repositories {
      mavenCentral()
      gradlePluginPortal()
      google()
      maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
      maven("https://oss.sonatype.org/content/repositories/snapshots")
      // Workspace fallback: resolve Gradle plugins from the internal Nexus mirror when
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
  }
}
