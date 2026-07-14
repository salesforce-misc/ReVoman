/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Phase
import org.junit.jupiter.api.Test

class RunbookStepEventTest {
  @Test
  fun `PhaseEntered path derives from phase name`() {
    assertThat(StepEvent.PhaseEntered(Phase.SEED).path).isEqualTo("SEED")
  }

  @Test
  fun `runbook step events carry narration`() {
    val started = StepEvent.RunbookStepStarted("act", "schedule", Phase.ACT, setOf("id"), true)
    assertThat(started.intent).isEqualTo("schedule")
    assertThat(started.underTest).isTrue()
    val finished =
      StepEvent.RunbookStepFinished(
        "act",
        "schedule",
        Outcome.SUCCESS,
        mapOf("s" to "Success"),
        12L,
      )
    assertThat(finished.produced).containsEntry("s", "Success")
    val failed =
      StepEvent.RunbookContractFailed("act", "schedule", emptySet(), setOf("s"), emptyMap())
    assertThat(failed.missingProduced).containsExactly("s")
  }
}
