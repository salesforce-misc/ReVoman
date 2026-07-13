/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StepSpecTest {
  private fun anyKick(): Kick = Kick.configure().templatePath("pm-templates/v3/cf-stop").off()

  @Test
  fun `build snapshots spec into an immutable RunbookStep`() {
    val step =
      StepSpec()
        .apply {
          intent = "seed fixture"
          phase = Phase.SEED
          kick = anyKick()
        }
        .consumes("authToken")
        .produces("accountId", "shiftIds")
        .build()
    assertThat(step.intent).isEqualTo("seed fixture")
    assertThat(step.phase).isEqualTo(Phase.SEED)
    assertThat(step.consumes).containsExactly("authToken")
    assertThat(step.produces.keys).containsExactly("accountId", "shiftIds")
    assertThat(step.produces.values.all { it == null }).isTrue()
    assertThat(step.underTest).isFalse()
    assertThat(step.assertAfter).isNull()
  }

  @Test
  fun `produces with values and underTest are captured`() {
    val step =
      StepSpec()
        .apply {
          intent = "act"
          phase = Phase.ACT
          kick = anyKick()
        }
        .underTest()
        .produces("schedulingStatus" to "Success")
        .assertAfter { _, _ -> }
        .build()
    assertThat(step.underTest).isTrue()
    assertThat(step.produces).containsEntry("schedulingStatus", "Success")
    assertThat(step.assertAfter).isNotNull()
  }

  @Test
  fun `build without a kick fails fast`() {
    val ex =
      assertThrows<IllegalStateException> {
        StepSpec()
          .apply {
            intent = "no kick"
            phase = Phase.SETUP
          }
          .build()
      }
    assertThat(ex).hasMessageThat().contains("kick")
  }
}
