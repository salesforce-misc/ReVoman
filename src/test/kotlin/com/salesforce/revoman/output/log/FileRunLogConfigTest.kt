/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FileRunLogConfigTest {
  @Test
  fun `DEFAULT_ALL turns every content toggle on with the default heaviest-steps size`() {
    val cfg = FileRunLogConfig.DEFAULT_ALL
    cfg.libLogs shouldBe true
    cfg.steps shouldBe true
    cfg.perf shouldBe true
    cfg.outcome shouldBe true
    cfg.runbook shouldBe true
    cfg.heaviestSteps shouldBe FileRunLogConfig.DEFAULT_HEAVIEST_STEPS
    FileRunLogConfig.DEFAULT_HEAVIEST_STEPS shouldBe 10
  }
}
