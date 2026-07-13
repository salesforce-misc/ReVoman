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

class RunbookViewTest {
  private fun step(
    intent: String,
    phase: Phase,
    consumes: Set<String> = emptySet(),
    produces: Map<String, String?> = emptyMap(),
    underTest: Boolean = false,
  ) =
    RunbookStep(
      intent,
      phase,
      Kick.configure().templatePath("pm-templates/v3/cf-stop").off(),
      consumes,
      produces,
      underTest,
      null,
    )

  private fun rundown() =
    Rundown(
      mutableEnv = PostmanEnvironment(),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 0,
    )

  private fun rr() =
    RunbookRundown(
      "demo",
      listOf(
        step("login", Phase.SETUP, produces = mapOf("authToken" to null)) to rundown(),
        step(
          "schedule",
          Phase.ACT,
          consumes = setOf("authToken"),
          produces = mapOf("status" to "Success"),
        ) to rundown(),
      ),
    )

  @Test
  fun `markdown lists steps with phase, consumes, produces`() {
    val md = rr().toMarkdown()
    assertThat(md).contains("| Phase | Step |")
    assertThat(md).contains("SETUP")
    assertThat(md).contains("login")
    assertThat(md).contains("authToken")
    assertThat(md).contains("status=Success")
  }

  @Test
  fun `mermaid is a sequence diagram naming each step intent`() {
    val mmd = rr().toMermaid()
    assertThat(mmd).startsWith("sequenceDiagram")
    assertThat(mmd).contains("login")
    assertThat(mmd).contains("schedule")
  }

  @Test
  fun `mermaid shows consumes annotation and diamond marker for under-test steps`() {
    val rrWithUnderTest =
      RunbookRundown(
        "mermaid test",
        listOf(
          step("seed data", Phase.SETUP, produces = mapOf("authToken" to null)) to rundown(),
          step(
            "act",
            Phase.ACT,
            consumes = setOf("authToken"),
            produces = mapOf("result" to "ok"),
            underTest = true,
          ) to rundown(),
        ),
      )
    val mmd = rrWithUnderTest.toMermaid()
    // Assert consumes annotation (⟵ authToken) and under-test marker (◆) appear for the under-test
    // step.
    assertThat(mmd).contains("⟵ authToken")
    assertThat(mmd).contains("◆")
  }
}
