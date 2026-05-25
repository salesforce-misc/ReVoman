/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import org.junit.jupiter.api.Test

class V3DetectionTest {
  @Test
  fun testRevUpAcceptsV3DirectoryPathAndLoadsAllSteps() {
    val rundown =
      ReVoman.revUp(Kick.configure().templatePath("pm-templates/v3/flat").insecureHttp(true).off())
    assertThat(rundown.providedStepsToExecuteCount).isEqualTo(3)
  }

  /**
   * Regression for `URI is not hierarchical` when a v2 single-file JSON collection is loaded via
   * the classloader from a jar-backed resource (FTest scenario). resolveV3CollectionDir must return
   * null for non-`file:` URLs so the caller routes to the v2 JSON adapter path.
   */
  @Test
  fun testRevUpAcceptsV2SingleFileCollectionPathFromClasspath() {
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("pm-templates/v2/steps-without-folders.postman_collection.json")
          .insecureHttp(true)
          .off()
      )
    // Just verify it loaded steps — no URI exception.
    assertThat(rundown.providedStepsToExecuteCount).isGreaterThan(0)
  }
}
