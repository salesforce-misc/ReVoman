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
}
