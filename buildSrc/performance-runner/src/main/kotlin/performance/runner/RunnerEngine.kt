/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.runner

/** Executes a validated runner command without depending on Gradle APIs. */
class RunnerEngine(private val dependencies: RunnerDependencies) {
  fun execute(command: RunnerCommand): RunnerOutcome {
    val reason =
      when (command) {
        is RunnerCommand.ValidateDistribution,
        is RunnerCommand.Capture,
        is RunnerCommand.Compare,
        is RunnerCommand.Campaign,
        is RunnerCommand.ScrubProfiler,
        is RunnerCommand.FinalizeDiagnostic,
        is RunnerCommand.FinalizeCampaign,
        is RunnerCommand.Recover -> RunnerFailureReason.COMMAND_NOT_AVAILABLE
      }
    dependencies.reportInputFailure(reason)
    return RunnerOutcome(
      exit = RunnerExit.INPUT_OR_PREFLIGHT_INVALID,
      publishedArtifact = null,
    )
  }
}
