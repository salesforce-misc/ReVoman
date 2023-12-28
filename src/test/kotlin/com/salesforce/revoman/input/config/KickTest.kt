/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test

class KickTest {
  @Test
  fun `'runOnlySteps' and 'skipSteps' cannot have intersection`() {
    shouldThrow<IllegalArgumentException> {
      Kick.configure().skipSteps("a", "b").runOnlySteps("b", "c").off()
    }
  }

  @Test
  fun `'haltOnAnyFailureExceptForSteps' should be empty when 'haltOnAnyFailure' is set to True`() {
    shouldThrow<IllegalArgumentException> {
      Kick.configure().haltOnAnyFailure(true).haltOnAnyFailureExceptForSteps("a", "b").off()
    }
  }
}
