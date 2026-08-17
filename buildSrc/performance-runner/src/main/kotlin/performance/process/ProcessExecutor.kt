/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.process

import java.nio.file.Path

/** The complete, immutable specification for one external process. */
data class ProcessSpec(
  val executable: Path,
  val arguments: List<String>,
  val classpath: List<Path>,
  val workingDirectory: Path,
  val environment: Map<String, String>,
  val stdoutPath: Path,
  val stderrPath: Path,
  val resultPath: Path,
  val rawProfilerPath: Path? = null,
)

/** The process exit observed only after the child has terminated. */
data class ProcessResult(val exitCode: Int)

/** The sole external-process seam used by performance capture. */
fun interface ProcessExecutor {
  fun execute(spec: ProcessSpec): ProcessResult
}
