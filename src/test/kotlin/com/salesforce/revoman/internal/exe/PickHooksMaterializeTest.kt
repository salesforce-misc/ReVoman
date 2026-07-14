/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either.Right
import com.salesforce.revoman.input.config.HookConfig
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxnInfo
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

/**
 * Locks the D2 invariant: the pick predicate of each configured pre/post step hook is evaluated
 * EXACTLY ONCE per step, and the picked-hook count set on the step is correct.
 *
 * Rationale: the boundary functions [pickPreStepHooks]/[pickPostStepHooks] are `private`, so this
 * exercises them through the same-package `internal` consumers [preStepHookExe]/[postStepHookExe]
 * with a real [Kick]. Each hook's [PreTxnStepPick]/[PostTxnStepPick] increments a shared
 * [AtomicInteger]; with two always-true hooks the filter must run the predicate exactly twice (once
 * per hook).
 *
 * On the pre-D2 `Sequence`-returning implementation the terminal `.also { it.iterator().hasNext();
 * it.count(); it.count() }` plus the downstream `.map { }.firstOrNull { }` re-evaluate the lazy
 * filter multiple times, so the counter overshoots (observed > 2). Materializing to a `List` once
 * makes the filter eager, so the predicate runs exactly once per hook.
 */
class PickHooksMaterializeTest {

  private val moshiReVoman = initMoshi()

  private fun requestInfo(): TxnInfo<org.http4k.core.Request> {
    val rawRequest =
      Request(method = POST.toString(), url = Url("https://overfullstack.github.io/"))
    return TxnInfo(
      txnObjType = String::class.java,
      txnObj = "fakeRequest",
      httpMsg = rawRequest.toHttpRequest(moshiReVoman),
      moshiReVoman = moshiReVoman,
    )
  }

  private fun rundown(): Rundown =
    Rundown(
      mutableEnv = PostmanEnvironment(),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 0,
    )

  @Test
  fun `pre-step hook pick predicate runs exactly once per configured hook`() {
    val predicateInvocations = AtomicInteger(0)
    val countingPick = PreTxnStepPick { _, _, _ ->
      predicateInvocations.incrementAndGet()
      true
    }
    val noOpHook = HookConfig.StepHook.PreStepHook { _, _, _ -> }
    val kick =
      Kick.configure()
        .templatePath("unused")
        .hooks(HookConfig.pre(countingPick, noOpHook), HookConfig.pre(countingPick, noOpHook))
        .off()
    val currentStep = Step(index = "1", rawPMStep = Item(name = "pre-hook-step"))

    val failure = preStepHookExe(currentStep, kick, requestInfo(), rundown())

    failure shouldBe null
    // Both hooks were picked and the count was recorded on the step.
    currentStep.preStepHookCount shouldBe 2
    // The pick predicate ran EXACTLY once per configured hook (2), not 3-4x from a re-scanned
    // Sequence.
    predicateInvocations.get() shouldBe 2
  }

  @Test
  fun `post-step hook pick predicate runs exactly once per configured hook`() {
    val predicateInvocations = AtomicInteger(0)
    val countingPick = PostTxnStepPick { _, _ ->
      predicateInvocations.incrementAndGet()
      true
    }
    val noOpHook = HookConfig.StepHook.PostStepHook { _, _ -> }
    val kick =
      Kick.configure()
        .templatePath("unused")
        .hooks(HookConfig.post(countingPick, noOpHook), HookConfig.post(countingPick, noOpHook))
        .off()
    val responseInfo: TxnInfo<Response> =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeResponse",
        httpMsg = Response(OK),
        moshiReVoman = moshiReVoman,
      )
    val currentStepReport =
      StepReport(
        Step(index = "1", rawPMStep = Item(name = "post-hook-step")),
        Right(requestInfo()),
        null,
        Right(responseInfo),
        pmEnvSnapshot = PostmanEnvironment(),
      )

    val failure = postStepHookExe(kick, currentStepReport, rundown())

    failure shouldBe null
    // Both hooks were picked and the count was recorded on the step.
    currentStepReport.step.postStepHookCount shouldBe 2
    // The pick predicate ran EXACTLY once per configured hook (2), not the ~7x a re-scanned
    // Sequence
    // produced before D2.
    predicateInvocations.get() shouldBe 2
  }

  @Test
  fun `pre-step hook execution short-circuits - a hook after a failing one does not run`() {
    // Guards that D2's Sequence->List of the PICK did NOT make hook EXECUTION eager: the consumer
    // still runs picked hooks lazily and stops at the first failure, so a later hook's accept()
    // (with its side effects) must NOT fire once an earlier hook has failed. Pre-D2 behavior.
    val alwaysPick = PreTxnStepPick { _, _, _ -> true }
    val secondHookRan = AtomicInteger(0)
    val failingHook = HookConfig.StepHook.PreStepHook { _, _, _ -> error("boom from first hook") }
    val secondHook = HookConfig.StepHook.PreStepHook { _, _, _ -> secondHookRan.incrementAndGet() }
    val kick =
      Kick.configure()
        .templatePath("unused")
        .hooks(HookConfig.pre(alwaysPick, failingHook), HookConfig.pre(alwaysPick, secondHook))
        .off()
    val currentStep = Step(index = "1", rawPMStep = Item(name = "short-circuit-step"))

    val failure = preStepHookExe(currentStep, kick, requestInfo(), rundown())

    // The first hook failed -> a failure is returned...
    (failure != null) shouldBe true
    // ...and the second hook's accept() NEVER ran (lazy short-circuit preserved).
    secondHookRan.get() shouldBe 0
  }

  @Test
  fun `post-step hook execution short-circuits - a hook after a failing one does not run`() {
    // Same guard as the pre path but for postStepHookExe (independently edited, identical
    // structure):
    // a picked post-hook after a failing one must NOT run its accept() side effects.
    val alwaysPick = PostTxnStepPick { _, _ -> true }
    val secondHookRan = AtomicInteger(0)
    val failingHook =
      HookConfig.StepHook.PostStepHook { _, _ -> error("boom from first post hook") }
    val secondHook = HookConfig.StepHook.PostStepHook { _, _ -> secondHookRan.incrementAndGet() }
    val kick =
      Kick.configure()
        .templatePath("unused")
        .hooks(HookConfig.post(alwaysPick, failingHook), HookConfig.post(alwaysPick, secondHook))
        .off()
    val responseInfo: TxnInfo<Response> =
      TxnInfo(
        txnObjType = String::class.java,
        txnObj = "fakeResponse",
        httpMsg = Response(OK),
        moshiReVoman = moshiReVoman,
      )
    val currentStepReport =
      StepReport(
        Step(index = "1", rawPMStep = Item(name = "post-short-circuit-step")),
        Right(requestInfo()),
        null,
        Right(responseInfo),
        pmEnvSnapshot = PostmanEnvironment(),
      )

    val failure = postStepHookExe(kick, currentStepReport, rundown())

    (failure != null) shouldBe true
    secondHookRan.get() shouldBe 0
  }
}
