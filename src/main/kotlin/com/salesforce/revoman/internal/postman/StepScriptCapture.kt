/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.output.report.PmTestAssertion
import com.salesforce.revoman.output.report.Step

/** Transitional Step-keyed script capture; CS2b replaces the Step keys with execution identity. */
internal interface StepScriptCapture {
  @JvmSynthetic fun reset(step: Step)

  @JvmSynthetic fun recordAssertions(step: Step, assertions: List<PmTestAssertion>)

  @JvmSynthetic fun recordNextRequest(step: Step, value: String?, wasSet: Boolean)

  @JvmSynthetic fun recordSkipRequest(step: Step)

  @JvmSynthetic fun assertionsFor(step: Step): List<PmTestAssertion>

  @JvmSynthetic fun nextRequestFor(step: Step): String?

  @JvmSynthetic fun nextRequestWasSetFor(step: Step): Boolean

  @JvmSynthetic fun skipRequestFor(step: Step): Boolean
}

@JvmSynthetic
internal fun stepScriptCapture(): StepScriptCapture =
  object : StepScriptCapture {
    private val assertionsByStep = mutableMapOf<Step, List<PmTestAssertion>>()
    private val nextRequestByStep = mutableMapOf<Step, String?>()
    private val nextRequestWasSetByStep = mutableMapOf<Step, Boolean>()
    private val skipRequestByStep = mutableMapOf<Step, Boolean>()

    @JvmSynthetic
    override fun reset(step: Step) {
      assertionsByStep.remove(step)
      nextRequestByStep.remove(step)
      nextRequestWasSetByStep.remove(step)
      skipRequestByStep.remove(step)
    }

    @JvmSynthetic
    override fun recordAssertions(step: Step, assertions: List<PmTestAssertion>) {
      assertionsByStep[step] = (assertionsByStep[step] ?: emptyList()) + assertions
    }

    @JvmSynthetic
    override fun recordNextRequest(step: Step, value: String?, wasSet: Boolean) {
      nextRequestByStep[step] = value
      nextRequestWasSetByStep[step] = wasSet
    }

    @JvmSynthetic
    override fun recordSkipRequest(step: Step) {
      skipRequestByStep[step] = true
    }

    @JvmSynthetic
    override fun assertionsFor(step: Step): List<PmTestAssertion> =
      assertionsByStep[step] ?: emptyList()

    @JvmSynthetic override fun nextRequestFor(step: Step): String? = nextRequestByStep[step]

    @JvmSynthetic
    override fun nextRequestWasSetFor(step: Step): Boolean = nextRequestWasSetByStep[step] ?: false

    @JvmSynthetic
    override fun skipRequestFor(step: Step): Boolean = skipRequestByStep[step] ?: false
  }
