/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.process

import com.salesforce.revoman.benchmark.driver.model.TargetForkResult
import java.nio.file.Path
import java.time.Duration

/** A shell-free JVM invocation whose executable, classpath, and arguments remain distinct values. */
data class JavaCommand(
    val executable: Path,
    val jvmArgs: List<String>,
    val classpath: List<Path>,
    val mainClass: String,
    val programArgs: List<String>,
    val workingDirectory: Path,
    val timeout: Duration,
    val invocationPrefix: List<String> = emptyList(),
)

/** Complete parent-side evidence retained from one isolated target process. */
data class ProcessObservation(
    val exitCode: Int,
    val processId: Long,
    val elapsedNanos: Long,
    val stdoutTail: String,
    val stderrTail: String,
    val result: TargetForkResult,
    val launcherProcessId: Long = processId,
)

/** Bounded output and process identity returned by one strict JMH controller launch. */
data class JmhControllerObservation(
    val exitCode: Int,
    val processId: Long,
    val stdoutTail: String,
    val stderrTail: String,
)

/** Launches exactly one process for [command]. */
fun interface ProcessLauncher {
    fun launch(command: JavaCommand): ProcessObservation
}
