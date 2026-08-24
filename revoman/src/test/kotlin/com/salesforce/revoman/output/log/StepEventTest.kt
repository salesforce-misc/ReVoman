/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StepEventTest {

  @Test
  fun `StepFinished defaults new coordinate fields to null`() {
    val e =
      StepEvent.StepFinished(
        path = "s",
        httpStatus = 200,
        produced = emptySet(),
        consumed = emptySet(),
        tookMs = 1L,
        outcome = Outcome.SUCCESS,
      )
    e.method.shouldBeNull()
    e.host.shouldBeNull()
    e.path shouldBe "s"
  }

  @Test
  fun `StepFinished carries method host path when supplied`() {
    val e =
      StepEvent.StepFinished(
        path = "api/v2/pokemon/ditto",
        httpStatus = 200,
        produced = emptySet(),
        consumed = emptySet(),
        tookMs = 1L,
        outcome = Outcome.SUCCESS,
        method = "GET",
        host = "pokeapi.co",
      )
    e.method shouldBe "GET"
    e.host shouldBe "pokeapi.co"
  }
}
