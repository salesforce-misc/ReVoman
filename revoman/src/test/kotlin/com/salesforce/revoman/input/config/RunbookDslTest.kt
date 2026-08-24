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

class RunbookDslTest {
  private fun anyKick(name: String): Kick =
    Kick.configure().templatePath("pm-templates/v3/$name").off()

  @Test
  fun `Kotlin receiver DSL builds ordered steps`() {
    val runbook =
      Runbook(name = "wfs double book") {
        step {
          intent = "login as admin"
          phase = Phase.SETUP
          kick = anyKick("cf-stop")
          produces("authToken")
        }
        step {
          intent = "schedule"
          phase = Phase.ACT
          kick = anyKick("cf-loop")
          underTest()
          consumes("authToken")
          produces("schedulingStatus" to "Success")
        }
      }
    assertThat(runbook.name).isEqualTo("wfs double book")
    assertThat(runbook.steps.map { it.intent })
      .containsExactly("login as admin", "schedule")
      .inOrder()
    assertThat(runbook.steps[1].underTest).isTrue()
    assertThat(runbook.steps[1].consumes).containsExactly("authToken")
    assertThat(runbook.steps[1].produces).containsEntry("schedulingStatus", "Success")
  }

  @Test
  fun `building a runbook with duplicate step intents fails fast`() {
    val ex =
      assertThrows<IllegalArgumentException> {
        Runbook {
          step {
            intent = "login"
            phase = Phase.SETUP
            kick = anyKick("cf-stop")
          }
          step {
            intent = "login"
            phase = Phase.ACT
            kick = anyKick("cf-loop")
          }
        }
      }
    assertThat(ex).hasMessageThat().contains("distinct")
    assertThat(ex).hasMessageThat().contains("login")
  }
}
