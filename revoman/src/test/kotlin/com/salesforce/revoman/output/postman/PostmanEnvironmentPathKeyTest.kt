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

class PostmanEnvironmentPathKeyTest {
  @Test
  fun `two set()s on the same step accumulate the union (path-keyed)`() {
    val env = PostmanEnvironment<Any?>()
    val step = Step(index = "1", rawPMStep = Item(name = "create-sa"))
    env.currentStep = step
    env.set("saId1", "a")
    env.set("saId2", "b")
    assertThat(env.producedKeysFor(step)).containsExactly("saId1", "saId2")
  }

  @Test
  fun `producedKeysFor resolves by path, not Step-equality (same path, different index)`() {
    val env = PostmanEnvironment<Any?>()
    val step1 = Step(index = "1", rawPMStep = Item(name = "create-sa"))
    env.currentStep = step1
    env.set("saId1", "a")
    // A Step with the SAME path (root step path == name == "create-sa") but a DIFFERENT index, so
    // it is NOT Step-equal to step1 (Step's data-class equals covers index/rawPMStep/parentFolder).
    // Under the old Step-keyed maps this lookup would MISS (empty); under path-keying it resolves.
    // This is the assertion that actually distinguishes path-keying from Step-keying (would be RED
    // on the pre-D4 code), guarding against a silent revert.
    val samePathDifferentIndex = Step(index = "2", rawPMStep = Item(name = "create-sa"))
    assertThat(samePathDifferentIndex.path).isEqualTo(step1.path)
    assertThat(samePathDifferentIndex).isNotEqualTo(step1)
    assertThat(env.producedKeysFor(samePathDifferentIndex)).containsExactly("saId1")
  }
}
