/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
plugins {
  `java-library`
  `maven-publish`
  signing
  id("org.jetbrains.kotlinx.kover")
}

repositories { mavenCentral() }

java {
  withSourcesJar()
  toolchain { languageVersion.set(JavaLanguageVersion.of(11)) }
}
