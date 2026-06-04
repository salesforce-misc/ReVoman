/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PmSandboxEnvDiffTest {
  @Test
  fun `diff detects produced changed and unset keys`() {
    val before = mapOf("keep" to "1", "change" to "old", "remove" to "x")
    val after = mapOf("keep" to "1", "change" to "new", "add" to "y")
    val diff = diffScopes(before, after)
    diff.produced shouldContainExactlyInAnyOrder listOf("change", "add")
    diff.unset shouldContainExactlyInAnyOrder listOf("remove")
  }

  @Test
  fun `no changes yields empty diff`() {
    val same = mapOf("a" to "1")
    val diff = diffScopes(same, same)
    diff.produced shouldBe emptySet()
    diff.unset shouldBe emptySet()
  }
}
