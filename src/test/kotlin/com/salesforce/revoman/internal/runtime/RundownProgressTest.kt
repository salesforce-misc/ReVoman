/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RundownProgressTest {
  @Test
  fun `update replaces only the final report for the same Step`() {
    val firstStep = step("first")
    val loopedStep = step("loop")
    val first = report(firstStep)
    val priorIteration = report(loopedStep).copy(iteration = 0, nextRequest = "loop")
    val currentSeed = report(loopedStep).copy(iteration = 1)
    val evolved = currentSeed.copy(nextRequest = "done")
    val progress = RundownProgress()
    progress.begin(currentSeed, rundown(listOf(first, priorIteration, currentSeed)))

    progress.update(evolved)

    progress.rundown.stepReports shouldContainExactly listOf(first, priorIteration, evolved)
    progress.currentReport shouldBe evolved
  }

  @Test
  fun `update appends when the final report belongs to another Step`() {
    val first = report(step("first"))
    val second = report(step("second"))
    val progress = RundownProgress()
    progress.begin(first, rundown(listOf(first)))

    progress.update(second)

    progress.rundown.stepReports shouldContainExactly listOf(first, second)
  }

  @Test
  fun `current request name follows the current report`() {
    val first = report(step("first"))
    val second = report(step("second"))
    val progress = RundownProgress()
    progress.begin(first, rundown(listOf(first)))
    progress.begin(second, rundown(listOf(first, second)))

    progress.currentRequestName shouldBe "second"
  }

  private fun step(name: String): Step = Step(index = name, rawPMStep = Item(name = name))

  private fun report(step: Step): StepReport =
    StepReport(step = step, pmEnvSnapshot = PostmanEnvironment())

  private fun rundown(reports: List<StepReport>): Rundown =
    Rundown(
      stepReports = reports,
      mutableEnv = PostmanEnvironment(),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = reports.size,
    )
}
