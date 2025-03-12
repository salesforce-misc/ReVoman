/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
plugins {
  kotlin("jvm")
  kotlin("kapt")
}

val libs: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies { testImplementation(libs.kotestBundle) }

kapt {
  useBuildCache = true
}

kotlin {
  jvmToolchain(libs.jdk.toString().toInt())
  compilerOptions {
    freeCompilerArgs.addAll("-Xjvm-default=all", "-Xcontext-receivers", "-Xconsistent-data-class-copy-visibility", "-Xmulti-dollar-interpolation")
  } 
}
