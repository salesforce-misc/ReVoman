/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: 
 *           Apache License Version 2.0 
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
  kotlin("kapt")
}

val libs: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies { testImplementation(libs.kotestBundle) }

tasks {
  withType<KotlinCompile> {
    kotlinOptions {
      // ! "-Xjvm-default=all" is needed for Immutables to work with Kotlin default methods
      // https://kotlinlang.org/docs/java-to-kotlin-interop.html#compatibility-modes-for-default-methods
      freeCompilerArgs = listOf("-Xjvm-default=all", "-Xcontext-receivers", "-Xjdk-release=11")
    }
  }
}
