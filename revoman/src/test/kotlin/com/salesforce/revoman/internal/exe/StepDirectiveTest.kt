/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import org.junit.jupiter.api.Test

class StepDirectiveTest {

  // directiveOf: derive None / Jump / Stop from a report's nextRequestSet + nextRequest.
  @Test
  fun `directiveOf returns None when setNextRequest never called`() {
    assertThat(directiveFromRaw(set = false, name = null)).isEqualTo(StepDirective.None)
  }

  @Test
  fun `directiveOf returns Stop when set with null or blank name`() {
    assertThat(directiveFromRaw(set = true, name = null)).isEqualTo(StepDirective.Stop)
    assertThat(directiveFromRaw(set = true, name = "  ")).isEqualTo(StepDirective.Stop)
  }

  @Test
  fun `directiveOf returns Jump when set with a name`() {
    assertThat(directiveFromRaw(set = true, name = "target"))
      .isEqualTo(StepDirective.Jump("target"))
  }

  // resolveTarget: first picked step matching by name, else null (unresolved).
  @Test
  fun `resolveTarget finds the index of the matching picked step`() {
    val steps = listOf(stepNamed("a"), stepNamed("b"), stepNamed("c"))
    assertThat(resolveTarget("c", steps, fromCursor = 0)).isEqualTo(2)
  }

  @Test
  fun `resolveTarget returns null when no picked step matches`() {
    val steps = listOf(stepNamed("a"), stepNamed("b"))
    assertThat(resolveTarget("nope", steps, fromCursor = 0)).isNull()
  }

  private fun directiveFromRaw(set: Boolean, name: String?): StepDirective {
    val report =
      StepReport(
        step = stepNamed("test"),
        pmEnvSnapshot = PostmanEnvironment(),
        nextRequestSet = set,
        nextRequest = name,
      )
    return directiveOf(report)
  }

  private fun stepNamed(name: String): Step = Step(index = "1", rawPMStep = Item(name = name))
}
