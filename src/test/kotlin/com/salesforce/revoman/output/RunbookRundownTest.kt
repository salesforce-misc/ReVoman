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

  @Test
  fun `equals and hashCode delegate to the backing rundown list, matching List semantics`() {
    val a = rundown()
    val b = rundown()
    val rr = RunbookRundown("rb", listOf(step("login") to a, step("act") to b))
    val plainList = listOf(a, b)
    // Symmetric structural equality with a plain List<Rundown> (both directions).
    assertThat(rr).isEqualTo(plainList)
    assertThat(plainList).isEqualTo(rr)
    // hashCode matches the backing list (required by the equals/hashCode contract).
    assertThat(rr.hashCode()).isEqualTo(plainList.hashCode())
    // Two RunbookRundowns with the SAME rundowns are equal even if name/pairing differ.
    val rrSameRundownsDifferentName =
      RunbookRundown("other-name", listOf(step("x") to a, step("y") to b))
    assertThat(rr).isEqualTo(rrSameRundownsDifferentName)
    // A different rundown list is not equal.
    assertThat(rr).isNotEqualTo(listOf(a))
  }
}
