/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.salesforce.revoman.input.config.StepPick.ExeStepPick
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.report.Step
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class StepPickTest {
  private fun sampleStep(): Step =
    Step(
      "1",
      Item(name = "step-1", request = Request(method = "GET", url = Url("https://example.com"))),
    )

  @Test
  fun `no picks defaults to run`() {
    val result = shouldStepBePicked(sampleStep(), emptyList(), emptyList())
    result shouldBe true
  }

  @Test
  fun `skip pick prevents execution`() {
    val skip = listOf(ExeStepPick { true })
    val result = shouldStepBePicked(sampleStep(), emptyList(), skip)
    result shouldBe false
  }

  @Test
  fun `run and skip picks are ambiguous`() {
    val picks = listOf(ExeStepPick { true })
    val failure = shouldThrow<IllegalStateException> { shouldStepBePicked(sampleStep(), picks, picks) }
    failure.message shouldContain "Ambiguous"
  }
}
