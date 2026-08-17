/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import performance.distribution.DistributionValidation
import performance.distribution.DistributionValidationRequest
import performance.distribution.DistributionValidator
import performance.distribution.JavaRuntimeIdentity
import performance.hash.Sha256

/** Revalidates a frozen distribution without launching any child process. */
@DisableCachingByDefault(because = "Validation proves the current selected Java executable")
abstract class VerifyPerformanceDistributionTask : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val distributionDirectory: DirectoryProperty

  @TaskAction
  fun verify() {
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
            root = distributionDirectory.get().asFile.toPath(),
            selectedJava =
              JavaRuntimeIdentity(
                executable = executable,
                featureVersion = Runtime.version().feature(),
                sha256 = Sha256.digest(Files.readAllBytes(executable)),
              ),
          ),
        )
    if (result is DistributionValidation.Invalid) {
      throw GradleException(
        "performance distribution is invalid: ${result.problems.joinToString(",")}",
      )
    }
  }
}
