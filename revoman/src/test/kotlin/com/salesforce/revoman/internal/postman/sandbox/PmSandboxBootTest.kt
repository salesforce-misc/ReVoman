/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PmSandboxBootTest {
  @Test
  fun `real pm API boots under GraalJS and runs pm test + environment set`() {
    val bridge = SandboxBridge()
    bridge.boot()
    val result =
      bridge.dispatchExecute(
        id = "boot1",
        script =
          """
          pm.environment.set('spikeKey', 'spikeVal-' + (1 + 1));
          pm.test('one plus one is two', function () { pm.expect(1 + 1).to.eql(2); });
          pm.test('env round-trips', function () {
            pm.expect(pm.environment.get('spikeKey')).to.eql('spikeVal-2');
          });
          pm.test('intentional failure', function () { pm.expect(true).to.eql(false); });
          """
            .trimIndent(),
        target = ScriptTarget.TEST,
        context = PmExecutionContext(environment = PmScope("env1", emptyMap())),
        timeoutMs = 5000,
      )
    bridge.close()

    result.error shouldBe null
    result.assertions shouldHaveSize 3
    result.assertions[0].passed shouldBe true
    result.assertions[1].passed shouldBe true
    result.assertions[2].passed shouldBe false
    result.environment["spikeKey"] shouldBe "spikeVal-2"
  }
}
