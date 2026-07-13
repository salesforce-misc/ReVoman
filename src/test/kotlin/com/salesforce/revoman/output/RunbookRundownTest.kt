/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.RunbookStep
import com.salesforce.revoman.output.postman.PostmanEnvironment
import org.junit.jupiter.api.Test

class RunbookRundownTest {
  private fun step(intent: String) =
    RunbookStep(
      intent,
      Phase.ACT,
      Kick.configure().templatePath("pm-templates/v3/cf-stop").off(),
      emptySet(),
      emptyMap(),
      false,
      null,
    )

  private fun rundown() =
    Rundown(
      mutableEnv = PostmanEnvironment(),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 0,
    )

  @Test
  fun `behaves as a List of Rundown in order`() {
    val a = rundown()
    val b = rundown()
    val rr = RunbookRundown("rb", listOf(step("login") to a, step("act") to b))
    assertThat(rr).hasSize(2)
    assertThat(rr[0]).isSameInstanceAs(a)
    assertThat(rr[1]).isSameInstanceAs(b)
    assertThat(rr.last()).isSameInstanceAs(b)
  }

  @Test
  fun `stepFor finds by intent`() {
    val a = rundown()
    val rr = RunbookRundown(null, listOf(step("login") to a))
    assertThat(rr.stepFor("login")?.second).isSameInstanceAs(a)
    assertThat(rr.stepFor("missing")).isNull()
  }
}
