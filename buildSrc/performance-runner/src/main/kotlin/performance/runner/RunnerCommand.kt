/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.runner

/** A syntactically valid command accepted by the frozen performance runner. */
sealed interface RunnerCommand {
  data class ValidateDistribution(val arguments: List<String>) : RunnerCommand

  data class Capture(val arguments: List<String>) : RunnerCommand

  data class Compare(val arguments: List<String>) : RunnerCommand

  data class Campaign(val arguments: List<String>) : RunnerCommand

  data class ScrubProfiler(val arguments: List<String>) : RunnerCommand

  data class FinalizeDiagnostic(val arguments: List<String>) : RunnerCommand

  data class FinalizeStandaloneComparison(val arguments: List<String>) : RunnerCommand

  data class FinalizeCampaign(val arguments: List<String>) : RunnerCommand

  data class FinalizeFreeze(val arguments: List<String>) : RunnerCommand

  data class Recover(val arguments: List<String>) : RunnerCommand
}
