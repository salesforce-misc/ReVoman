/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.runner

/** Executes a validated runner command without depending on Gradle APIs. */
internal class RunnerEngine(private val dependencies: RunnerDependencies) {
  fun execute(command: RunnerCommand): RunnerOutcome {
    dependencies.execute(command)?.let { return it }
    if (command is RunnerCommand.ValidateDistribution) {
      return validateDistribution(command)
    }
    val reason =
      when (command) {
        is RunnerCommand.Capture,
        is RunnerCommand.Compare,
        is RunnerCommand.Campaign,
        is RunnerCommand.ScrubProfiler,
        is RunnerCommand.FinalizeDiagnostic,
        is RunnerCommand.FinalizeStandaloneComparison,
        is RunnerCommand.FinalizeCampaign,
        is RunnerCommand.FinalizeFreeze,
        is RunnerCommand.Recover -> RunnerFailureReason.COMMAND_NOT_AVAILABLE
      }
    dependencies.reportInputFailure(reason)
    return RunnerOutcome(
      exit = RunnerExit.INPUT_OR_PREFLIGHT_INVALID,
      publishedArtifact = null,
    )
  }

  private fun validateDistribution(command: RunnerCommand.ValidateDistribution): RunnerOutcome {
    val path =
      command.arguments.windowed(size = 2, step = 2).singleOrNull {
        it.firstOrNull() == "--distribution"
      }?.getOrNull(1)
    return if (path != null && dependencies.distributionIsValid(path)) {
      RunnerOutcome(exit = RunnerExit.SUCCESS, publishedArtifact = null)
    } else {
      dependencies.reportInputFailure(RunnerFailureReason.DISTRIBUTION_INVALID)
      RunnerOutcome(exit = RunnerExit.INPUT_OR_PREFLIGHT_INVALID, publishedArtifact = null)
    }
  }
}
