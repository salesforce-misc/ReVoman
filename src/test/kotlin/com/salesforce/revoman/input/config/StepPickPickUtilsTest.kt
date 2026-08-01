/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import arrow.core.Either.Right
import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.StepPick.ExeStepPick.PickUtils.stepContainingURIPathOfAny
import com.salesforce.revoman.input.config.StepPick.ExeStepPick.PickUtils.stepEndingWithURIPathOfAny
import com.salesforce.revoman.input.config.StepPick.ExeStepPick.PickUtils.withName
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.PickUtils.afterStepContainingURIPathOfAny
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.PickUtils.afterStepName
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick.PickUtils.beforeStepEndingWithURIPathOfAny
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick.PickUtils.beforeStepName
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.TxnInfo
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class StepPickPickUtilsTest {
  private val moshiReVoman = initMoshi()

  private fun step(name: String, url: String = "https://x.test/v1/objects/foo") =
    Step(index = "1", rawPMStep = Item(name = name, request = Request(url = Url(url))))

  private fun requestInfo(
    url: String = "https://x.test/v1/objects/foo"
  ): TxnInfo<org.http4k.core.Request> =
    TxnInfo(
      txnObjType = String::class.java,
      txnObj = "req",
      httpMsg = Request(method = POST.toString(), url = Url(url)).toHttpRequest(moshiReVoman),
      moshiReVoman = moshiReVoman,
    )

  private fun responseInfo(): TxnInfo<Response> =
    TxnInfo(
      txnObjType = String::class.java,
      txnObj = "res",
      httpMsg = Response(OK),
      moshiReVoman = moshiReVoman,
    )

  private fun rundown(): Rundown =
    Rundown(
      mutableEnv = PostmanEnvironment(),
      haltOnFailureOfTypeExcept = emptyMap(),
      providedStepsToExecuteCount = 0,
    )

  private fun stepReport(name: String, url: String = "https://x.test/v1/objects/foo"): StepReport =
    StepReport(
      step(name, url),
      Right(requestInfo(url)),
      null,
      Right(responseInfo()),
      pmEnvSnapshot = PostmanEnvironment(),
    )

  @Test
  fun `ExeStepPick withName matches by exact step name`() {
    assertThat(withName("login").pick(step("login"))).isTrue()
    assertThat(withName("login").pick(step("logout"))).isFalse()
  }

  @Test
  fun `ExeStepPick uri picks match by raw URL`() {
    assertThat(stepEndingWithURIPathOfAny("objects/foo").pick(step("s"))).isTrue()
    assertThat(stepContainingURIPathOfAny("v1/objects").pick(step("s"))).isTrue()
    assertThat(stepContainingURIPathOfAny("nope").pick(step("s"))).isFalse()
  }

  @Test
  fun `PreTxnStepPick picks match name and uri`() {
    assertThat(beforeStepName("login").pick(step("login"), requestInfo(), rundown())).isTrue()
    assertThat(
        beforeStepEndingWithURIPathOfAny("objects/foo").pick(step("s"), requestInfo(), rundown())
      )
      .isTrue()
  }

  @Test
  fun `PostTxnStepPick picks match name and uri`() {
    assertThat(afterStepName("login").pick(stepReport("login"), rundown())).isTrue()
    assertThat(afterStepContainingURIPathOfAny("v1/objects").pick(stepReport("s"), rundown()))
      .isTrue()
  }
}
