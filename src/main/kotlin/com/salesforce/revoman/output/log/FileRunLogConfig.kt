/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

/**
 * The content-toggle knobs of a [FileRunLogSink]: which sections of a per-run log file get written.
 * A pure value type — a consumer supplies the values (e.g. Core reads them from
 * `~/.revoman/config.yaml`), and the library never learns WHERE they came from. The master on/off
 * switch and any "should we open a file at all" gate are the CONSUMER's orchestration, so there is
 * deliberately no `enabled` field here.
 *
 * @param libLogs tee the ReVoman library narration (INFO/DEBUG lines); WARN/ERROR always pass
 * @param steps per-step structured req/resp records
 * @param perf the perf tee lines + the perf summary block + the heaviest-steps table
 * @param outcome the pass/fail + failing-step + stacktrace footer
 * @param runbook the coarse runbook glyph brackets (phase rules, step open/close, contract lines)
 * @param heaviestSteps size of the heaviest-steps table
 */
data class FileRunLogConfig(
  val libLogs: Boolean,
  val steps: Boolean,
  val perf: Boolean,
  val outcome: Boolean,
  val runbook: Boolean,
  val heaviestSteps: Int,
) {
  companion object {
    /** Default size of the heaviest-steps table when a consumer supplies no explicit value. */
    const val DEFAULT_HEAVIEST_STEPS: Int = 10

    /** All-on default — the richest signal; a consumer degrades to this when config is absent. */
    @JvmField
    val DEFAULT_ALL: FileRunLogConfig =
      FileRunLogConfig(
        libLogs = true,
        steps = true,
        perf = true,
        outcome = true,
        runbook = true,
        heaviestSteps = DEFAULT_HEAVIEST_STEPS,
      )
  }
}
