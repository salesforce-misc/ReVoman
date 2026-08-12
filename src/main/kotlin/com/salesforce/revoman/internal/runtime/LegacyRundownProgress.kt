/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.StepReport

/** Transitional copied-Rundown progress state; CS4 replaces this legacy synchronization model. */
internal interface LegacyRundownProgress {
  @get:JvmSynthetic @set:JvmSynthetic var currentReport: StepReport

  @get:JvmSynthetic @set:JvmSynthetic var rundown: Rundown

  @get:JvmSynthetic @set:JvmSynthetic var currentRequestName: String

  @JvmSynthetic fun sync(report: StepReport)
}

@JvmSynthetic
internal fun legacyRundownProgress(): LegacyRundownProgress =
  object : LegacyRundownProgress {
    @get:JvmSynthetic @set:JvmSynthetic override lateinit var currentReport: StepReport

    @get:JvmSynthetic @set:JvmSynthetic override lateinit var rundown: Rundown

    @get:JvmSynthetic @set:JvmSynthetic override lateinit var currentRequestName: String

    @JvmSynthetic
    override fun sync(report: StepReport) {
      currentReport = report
      val reports = rundown.stepReports
      val updated =
        if (reports.lastOrNull()?.step == report.step) reports.dropLast(1) + report
        else reports + report
      rundown = rundown.copy(stepReports = updated)
    }
  }
