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

/** Mutable progress for the currently executing kick. Owned by one kick and never shared. */
internal class RundownProgress {
  @get:JvmSynthetic
  lateinit var currentReport: StepReport
    private set

  @get:JvmSynthetic
  lateinit var rundown: Rundown
    private set

  @get:JvmSynthetic
  val currentRequestName: String
    get() = currentReport.step.name

  @JvmSynthetic
  fun begin(report: StepReport, rundown: Rundown) {
    currentReport = report
    this.rundown = rundown
  }

  @JvmSynthetic
  fun update(report: StepReport) {
    currentReport = report
    val reports = rundown.stepReports
    val updated =
      if (reports.lastOrNull()?.step == report.step) reports.dropLast(1) + report
      else reports + report
    rundown = rundown.copy(stepReports = updated)
  }
}
