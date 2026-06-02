/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.report.Step
import org.junit.jupiter.api.Test

class RegexReplacerConsumedTest {
  @Test
  fun `resolving a double-brace var records it as consumed`() {
    val regexReplacer = RegexReplacer()
    val pm = PostmanSDK(initMoshi(), null, regexReplacer)
    pm.environment.currentStep = Step(index = "1", rawPMStep = Item(name = "validate"))
    // present in env (also marks produced for this step — fine)
    pm.environment.set("policyId", "0Pol1")
    val out = regexReplacer.replaceVariablesRecursively("id={{policyId}}", pm)
    assertThat(out).isEqualTo("id=0Pol1")
    assertThat(pm.environment.consumedKeysFor(pm.environment.currentStep)).contains("policyId")
  }
}
