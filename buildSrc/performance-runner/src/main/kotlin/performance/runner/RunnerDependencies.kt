/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.runner

import java.nio.file.Files
import java.nio.file.Path
import performance.distribution.DistributionValidation
import performance.distribution.DistributionValidationRequest
import performance.distribution.DistributionValidator
import performance.distribution.JavaRuntimeIdentity
import performance.hash.Sha256

/** Side-effecting process boundaries supplied to the pure runner engine. */
class RunnerDependencies(
  private val writeStandardError: (String) -> Unit,
  private val validateDistribution: (String) -> Boolean = { false },
) {
  internal fun reportInputFailure(reason: RunnerFailureReason) {
    writeStandardError("performance-runner: INPUT_OR_PREFLIGHT_INVALID: ${reason.name}")
  }

  internal fun distributionIsValid(path: String): Boolean = validateDistribution(path)

  internal companion object {
    fun system(): RunnerDependencies =
      RunnerDependencies(
        writeStandardError = { message -> System.err.println(message) },
        validateDistribution = ::validateSystemDistribution,
      )

    private fun validateSystemDistribution(rawPath: String): Boolean =
      runCatching {
          val executable =
            Path.of(
                checkNotNull(ProcessHandle.current().info().command().orElse(null)) {
                  "current Java executable is unavailable"
                },
              )
              .toAbsolutePath()
              .normalize()
          val result =
            DistributionValidator()
              .validate(
                DistributionValidationRequest(
                  root = Path.of(rawPath),
                  selectedJava =
                    JavaRuntimeIdentity(
                      executable = executable,
                      featureVersion = Runtime.version().feature(),
                      sha256 = Sha256.digest(Files.readAllBytes(executable)),
                    ),
                ),
              )
          result is DistributionValidation.Valid
        }
        .getOrDefault(false)
  }
}

internal enum class RunnerFailureReason {
  INVALID_COMMAND,
  INVALID_ARGUMENTS,
  DISTRIBUTION_INVALID,
  COMMAND_NOT_AVAILABLE,
}
