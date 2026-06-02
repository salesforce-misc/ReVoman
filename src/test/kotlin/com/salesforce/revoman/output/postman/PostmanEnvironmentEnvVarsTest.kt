/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.postman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.report.Step
import org.junit.jupiter.api.Test

class PostmanEnvironmentEnvVarsTest {
  private fun stepNamed(name: String): Step = Step(index = "1", rawPMStep = Item(name = name))

  @Test
  fun `set records produced key against current step`() {
    val env = PostmanEnvironment<Any?>()
    env.currentStep = stepNamed("create-sa")
    env.set("saId1", "08p1")
    assertThat(env.producedKeysFor(env.currentStep)).containsExactly("saId1")
  }

  @Test
  fun `index-set does NOT record as produced (regex write-back path)`() {
    val env = PostmanEnvironment<Any?>()
    env.currentStep = stepNamed("create-sa")
    env["someRegexKey"] = "v" // delegated map put, bypasses set()
    assertThat(env.producedKeysFor(env.currentStep)).isEmpty()
  }

  @Test
  fun `recordConsumed records read key against current step`() {
    val env = PostmanEnvironment<Any?>()
    env.currentStep = stepNamed("validate")
    env.recordConsumed("policyId")
    assertThat(env.consumedKeysFor(env.currentStep)).containsExactly("policyId")
  }

  @Test
  fun `unset removes a previously produced key`() {
    val env = PostmanEnvironment<Any?>()
    env.currentStep = stepNamed("create-sa")
    env.set("saId1", "08p1")
    env.unset("saId1")
    assertThat(env.producedKeysFor(env.currentStep)).isEmpty()
  }
}
