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

  @Test
  fun `six-arg constructor stays source-compatible and defaults diagram off`() {
    // Mirrors Core's positional Java call: new FileRunLogConfig(libLogs, steps, perf, outcome,
    // runbook, heaviestSteps). @JvmOverloads must regenerate this arity.
    val config = FileRunLogConfig(true, true, true, true, true, 10)
    config.diagram shouldBe false
  }

  @Test
  fun `diagram can be turned on via the seven-arg form`() {
    val config = FileRunLogConfig(true, true, true, true, true, 10, true)
    config.diagram shouldBe true
  }
}
