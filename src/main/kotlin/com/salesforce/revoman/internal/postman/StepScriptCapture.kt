/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.output.report.PmTestAssertion

internal class StepScriptCapture {
  private val capturedAssertions = mutableListOf<PmTestAssertion>()
  private var nextRequest: String? = null
  private var nextRequestWasSet = false
  private var skipRequest = false

  @JvmSynthetic
  fun reset() {
    capturedAssertions.clear()
    nextRequest = null
    nextRequestWasSet = false
    skipRequest = false
  }

  @JvmSynthetic
  fun recordAssertions(assertions: List<PmTestAssertion>) {
    capturedAssertions.addAll(assertions)
  }

  @JvmSynthetic
  fun recordNextRequest(value: String?, wasSet: Boolean) {
    nextRequest = value
    nextRequestWasSet = wasSet
  }

  @JvmSynthetic
  fun recordSkipRequest() {
    skipRequest = true
  }

  @JvmSynthetic fun assertions(): List<PmTestAssertion> = capturedAssertions.toList()

  @JvmSynthetic fun nextRequest(): String? = nextRequest

  @JvmSynthetic fun nextRequestWasSet(): Boolean = nextRequestWasSet

  @JvmSynthetic fun skipRequest(): Boolean = skipRequest
}
