/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance

import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.bundling.Jar

/** Packages only benchmark bytecode and generated JMH metadata, never application or test output. */
@CacheableTask
abstract class PerformanceBenchmarkJarTask : Jar() {
  init {
    archiveFileName.convention("revoman-jmh.jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
  }
}
