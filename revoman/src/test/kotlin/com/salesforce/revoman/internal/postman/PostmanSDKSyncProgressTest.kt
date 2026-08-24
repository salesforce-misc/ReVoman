/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * E1 guard: mirrors ReVoman.runStep — the Rundown is seeded with prior steps + THIS step's pre-step
 * report (current LAST), then syncProgress runs 3x per step. syncProgress must REPLACE the current
 * step's entry (not append), so the mid-run pm.rundown holds each step exactly once and prior steps
 * + loop-iteration history are preserved.
 */
class PostmanSDKSyncProgressTest {
  private fun step(name: String) = Step(index = "1", rawPMStep = Item(name = name))

  private fun report(step: Step) = StepReport(step = step, pmEnvSnapshot = PostmanEnvironment())

  private fun pmWithSeededRundown(stepReportsSoFar: List<StepReport>): PostmanSDK =
    PostmanSDK(initMoshi()).apply {
      rundown =
        Rundown(
          stepReports = stepReportsSoFar,
          mutableEnv = PostmanEnvironment(),
          haltOnFailureOfTypeExcept = emptyMap(),
          providedStepsToExecuteCount = stepReportsSoFar.size,
        )
    }

  @Test
  fun `three syncProgress calls keep the current step exactly once and preserve prior steps`() {
    val stepA = step("a")
    val stepB = step("b")
    val reportA = report(stepA)
    val preReportB = report(stepB)
    // ReVoman.kt:402 seed: prior steps + current step's pre-step report (current LAST).
    val pm = pmWithSeededRundown(listOf(reportA, preReportB))

    // ReVoman.kt:451,468,483: 3 syncProgress calls for the SAME step, each with an evolved sr.
    val srB1 = preReportB.copy(nextRequest = "afterHttp")
    val srB2 = srB1.copy(nextRequest = "afterPostRes")
    val srB3 = srB2.copy(nextRequest = "afterHooks")
    pm.syncProgress(srB1)
    pm.syncProgress(srB2)
    pm.syncProgress(srB3)

    pm.rundown.stepReports shouldHaveSize 2 // a + b, NOT a + b + b + b + b
    pm.rundown.stepReports.map { it.step.name } shouldBe listOf("a", "b")
    pm.rundown.stepReports.last() shouldBe srB3 // most-evolved report wins
    pm.currentStepReport shouldBe srB3
  }

  @Test
  fun `first syncProgress on an empty-prefix seed keeps a single entry`() {
    val stepA = step("a")
    val pre = report(stepA)
    val pm = pmWithSeededRundown(listOf(pre)) // first step of the run
    val evolved = pre.copy(nextRequest = "x")
    pm.syncProgress(evolved)
    pm.rundown.stepReports shouldHaveSize 1
    pm.rundown.stepReports.last() shouldBe evolved
  }

  @Test
  fun `looped step preserves the prior iteration report`() {
    val stepA = step("a")
    val iter0Final = report(stepA).copy(iteration = 0, nextRequest = "loop")
    val preIter1 = report(stepA).copy(iteration = 1)
    // 2nd iteration seed: prior iteration's final report + this iteration's pre-step report.
    val pm = pmWithSeededRundown(listOf(iter0Final, preIter1))
    val iter1Final = preIter1.copy(nextRequest = "done")
    pm.syncProgress(iter1Final)
    pm.rundown.stepReports shouldHaveSize 2 // iter0Final preserved, preIter1 replaced
    pm.rundown.stepReports[0] shouldBe iter0Final
    pm.rundown.stepReports[1] shouldBe iter1Final
  }
}
