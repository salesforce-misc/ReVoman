/*******************************************************************************
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 ******************************************************************************/

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("../libs.versions.toml")) }
    }

  pluginManagement {
    repositories {
      mavenCentral()
      gradlePluginPortal()
      google()
      maven("https://oss.sonatype.org/content/repositories/snapshots")
    }
  }
}
