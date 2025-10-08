/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
import com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA
import com.diffplug.spotless.LineEnding.PLATFORM_NATIVE
import com.diffplug.spotless.extra.wtp.EclipseWtpFormatterStep.XML
import io.gitlab.arturbosch.detekt.Detekt

plugins {
  id("com.diffplug.spotless")
  id("io.gitlab.arturbosch.detekt")
  id("com.adarshr.test-logger")
}

version = VERSION

group = GROUP_ID

description = "ReVoman - A Template-driven API automation tool for JVM (Java/Kotlin)"

repositories {
  mavenCentral()
  maven("https://oss.sonatype.org/content/repositories/snapshots")
}

spotless {
  lineEndings = PLATFORM_NATIVE
  kotlin {
    target("src/*/kotlin/**/*.kt", "src/*/java/**/*.kt")
    targetExclude("build/**", ".gradle/**", "generated/**", "**/bin/**", "out/**", "tmp/**")
    ktfmt().googleStyle()
    trimTrailingWhitespace()
    endWithNewline()
  }
  kotlinGradle {
    target("*.gradle.kts", "src/**/*.gradle.kts")
    targetExclude("build/**", ".gradle/**", "generated/**", "**/bin/**", "out/**", "tmp/**")
    ktfmt().googleStyle()
    trimTrailingWhitespace()
    endWithNewline()
  }
  java {
    target("src/*/java/**/*.java")
    targetExclude("build/**", ".gradle/**", "generated/**", "**/bin/**", "out/**", "tmp/**")
    googleJavaFormat()
    importOrder()
    removeUnusedImports()
    removeWildcardImports()
    trimTrailingWhitespace()
    leadingSpacesToTabs(2)
    endWithNewline()
  }
  format("documentation") {
    target("*.md", "*.adoc")
    trimTrailingWhitespace()
    leadingSpacesToTabs(2)
    endWithNewline()
  }
}

detekt {
  parallel = true
  buildUponDefaultConfig = true
  baseline = file("$rootDir/detekt/baseline.xml")
  config.setFrom(file("$rootDir/detekt/config.yml"))
}

testlogger.theme = MOCHA

tasks.withType<Detekt>().configureEach { reports { xml.required.set(true) } }
