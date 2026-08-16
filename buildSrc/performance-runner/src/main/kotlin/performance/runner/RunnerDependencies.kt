/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.runner

/** Side-effecting process boundaries supplied to the pure runner engine. */
class RunnerDependencies(private val writeStandardError: (String) -> Unit) {
  internal fun reportInputFailure(reason: RunnerFailureReason) {
    writeStandardError("performance-runner: INPUT_OR_PREFLIGHT_INVALID: ${reason.name}")
  }

  internal companion object {
    fun system(): RunnerDependencies =
      RunnerDependencies(writeStandardError = { message -> System.err.println(message) })
  }
}

internal enum class RunnerFailureReason {
  INVALID_COMMAND,
  INVALID_ARGUMENTS,
  COMMAND_NOT_AVAILABLE,
}
