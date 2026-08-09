/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.process

import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.TargetForkCommand
import com.salesforce.revoman.benchmark.driver.model.TargetForkResult
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile

/**
 * Launches child JVMs without a shell and rejects stale or in-place result writes.
 *
 * The trusted target worker separately publishes with a same-directory atomic move.
 */
class JdkProcessLauncher internal constructor(private val cleanup: LauncherCleanup) : ProcessLauncher {
    constructor() : this(DefaultLauncherCleanup)

    override fun launch(command: JavaCommand): ProcessObservation {
        validate(command)
        val commandFile = normalizedAbsolutePath("command file", command.programArgs.single())
        require(commandFile.isRegularFile()) { "Target command file does not exist: $commandFile" }
        val workerCommand = BenchmarkJson.read<TargetForkCommand>(commandFile)
        val resultFile = normalizedAbsolutePath("resultFile", workerCommand.resultFile)
        require(resultFile.parent == commandFile.parent && resultFile != commandFile) {
            "Target result file must be a distinct sibling of its command file"
        }
        Files.createDirectories(requireNotNull(resultFile.parent) { "resultFile must have a parent" })
        Files.deleteIfExists(resultFile)
        val atomicGuard = resultFile.resolveSibling(".${resultFile.fileName}.${UUID.randomUUID()}.guard")
        val guardBytes = "revoman-benchmark-atomic-result-guard/v1".toByteArray(UTF_8)
        val failures = FailureAccumulator()
        var process: Process? = null
        var drains: ExecutorService? = null
        var observation: ProcessObservation? = null
        var terminationAttempted = false
        try {
            Files.write(atomicGuard, guardBytes)
            Files.createLink(resultFile, atomicGuard)
            val arguments =
                buildList {
                    add(command.executable.toString())
                    addAll(command.jvmArgs)
                    add("-cp")
                    add(command.classpath.joinToString(System.getProperty("path.separator")))
                    add(command.mainClass)
                    addAll(command.programArgs)
                }
            val startedAt = System.nanoTime()
            process =
                ProcessBuilder(arguments)
                    .directory(command.workingDirectory.toFile())
                    .start()
            val launchedProcess = process
            val processId = launchedProcess.pid()
            drains = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory())
            val stdout = drains.submit(TailDrain(launchedProcess.inputStream))
            val stderr = drains.submit(TailDrain(launchedProcess.errorStream))
            observation =
                observeProcess(
                    process = launchedProcess,
                    processId = processId,
                    startedAt = startedAt,
                    timeout = command.timeout,
                    stdout = stdout,
                    stderr = stderr,
                    resultFile = resultFile,
                    atomicGuard = atomicGuard,
                    guardBytes = guardBytes,
                    terminate = {
                        terminationAttempted = true
                        cleanup.terminateProcessTree(launchedProcess)
                    },
                )
        } catch (failure: Throwable) {
            failures.add(failure)
        } finally {
            process?.let { launchedProcess ->
                val alive = runCatching(launchedProcess::isAlive)
                alive.exceptionOrNull()?.let(failures::add)
                if (!terminationAttempted && alive.getOrDefault(true)) {
                    failures.attempt { cleanup.terminateProcessTree(launchedProcess) }
                }
            }
            drains?.let { executor ->
                failures.attempt { cleanup.shutdownOutputDrains(executor) }
            }
            failures.attempt { cleanup.deleteGuard(atomicGuard) }
        }
        failures.throwIfAny()
        return requireNotNull(observation) { "Target process completed without an observation" }
    }

    private fun observeProcess(
        process: Process,
        processId: Long,
        startedAt: Long,
        timeout: Duration,
        stdout: Future<String>,
        stderr: Future<String>,
        resultFile: Path,
        atomicGuard: Path,
        guardBytes: ByteArray,
        terminate: () -> Unit,
    ): ProcessObservation {
        val finished =
            try {
                waitFor(process, timeout)
            } catch (failure: InterruptedException) {
                val interrupted =
                    IllegalStateException("Interrupted while waiting for target process $processId", failure)
                attachCleanupFailure(interrupted, terminate)
                Thread.currentThread().interrupt()
                throw interrupted
            }
        if (!finished) {
            val timeoutFailure =
                IllegalStateException("Target process $processId timed out after $timeout")
            attachCleanupFailure(timeoutFailure, terminate)
            throw timeoutFailure
        }
        val elapsedNanos = System.nanoTime() - startedAt
        val stdoutTail = finishDrain(stdout, process.inputStream)
        val stderrTail = finishDrain(stderr, process.errorStream)
        val exitCode = process.exitValue()
        check(exitCode == 0) {
            "Target process $processId exited with exit code $exitCode: " +
                diagnostics(stdoutTail, stderrTail)
        }
        requireReplacedResult(resultFile, atomicGuard, guardBytes, processId)
        val result =
            try {
                BenchmarkJson.read<TargetForkResult>(resultFile)
            } catch (failure: Throwable) {
                throw IllegalStateException(
                    "Target process $processId produced a malformed result file: $resultFile",
                    failure,
                )
            }
        check(result.processId == processId) {
            "Target result PID ${result.processId} differs from launched process PID $processId"
        }
        return ProcessObservation(
            exitCode = exitCode,
            processId = processId,
            elapsedNanos = elapsedNanos,
            stdoutTail = stdoutTail,
            stderrTail = stderrTail,
            result = result,
        )
    }

    private fun requireReplacedResult(
        resultFile: Path,
        atomicGuard: Path,
        guardBytes: ByteArray,
        processId: Long,
    ) {
        val guardUnchanged = Files.readAllBytes(atomicGuard).contentEquals(guardBytes)
        val replaced =
            resultFile.isRegularFile() &&
                runCatching { !Files.isSameFile(resultFile, atomicGuard) }.getOrDefault(false)
        when {
            !replaced && guardUnchanged ->
                error(
                    "Target process $processId produced an empty or missing result file: " +
                        resultFile
                )
            !replaced || !guardUnchanged ->
                error("Target process $processId did not replace the guarded result file: $resultFile")
            Files.size(resultFile) == 0L ->
                error("Target process $processId produced an empty or missing result file: $resultFile")
        }
    }

    private fun validate(command: JavaCommand) {
        require(command.executable.isAbsolute) { "Java executable must be absolute" }
        require(command.classpath.isNotEmpty()) { "Java classpath must not be empty" }
        require(command.classpath.all(Path::isAbsolute)) { "Java classpath entries must be absolute" }
        require(command.mainClass.isNotBlank()) { "Java mainClass must not be blank" }
        require(command.programArgs.size == 1) {
            "Target worker requires exactly one command-file argument"
        }
        require(command.workingDirectory.isAbsolute && Files.isDirectory(command.workingDirectory)) {
            "Java workingDirectory must be an existing absolute directory"
        }
        require(!command.timeout.isZero && !command.timeout.isNegative) {
            "Java timeout must be positive"
        }
    }

    private fun normalizedAbsolutePath(name: String, value: String): Path {
        val path = Path.of(value)
        require(path.isAbsolute && path.normalize() == path) { "$name must be absolute and normalized: $path" }
        return path
    }

    private fun waitFor(process: Process, timeout: Duration): Boolean =
        try {
            process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("Java timeout is too large: $timeout", failure)
        }

    private fun finishDrain(future: Future<String>, stream: InputStream): String =
        try {
            future.get(5, TimeUnit.SECONDS)
        } catch (failure: Throwable) {
            val drainFailure = IllegalStateException("Could not drain child process output", failure)
            attachCleanupFailure(drainFailure, stream::close)
            attachCleanupFailure(drainFailure) { future.cancel(true) }
            throw drainFailure
        }

    private fun diagnostics(stdout: String, stderr: String): String =
        "stdoutTail=${stdout.ifEmpty { "<empty>" }}, stderrTail=${stderr.ifEmpty { "<empty>" }}"
}

internal interface LauncherCleanup {
    fun terminateProcessTree(process: Process)

    fun shutdownOutputDrains(executor: ExecutorService)

    fun deleteGuard(path: Path)
}

internal object DefaultLauncherCleanup : LauncherCleanup {
    override fun terminateProcessTree(process: Process) {
        val failures = FailureAccumulator()
        var interrupted = false
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                if (failure is InterruptedException) interrupted = true
                failures.add(failure)
            }
        }

        val root = process.toHandle()
        var descendants = emptyList<ProcessHandle>()
        attempt { descendants = root.descendants().use { it.toList() } }
        descendants.asReversed().forEach { descendant -> attempt { descendant.destroy() } }
        attempt { root.destroy() }
        attempt { process.waitFor(1, TimeUnit.SECONDS) }
        descendants.asReversed().forEach { descendant -> attempt { descendant.destroyForcibly() } }
        attempt { root.destroyForcibly() }
        attempt { process.waitFor(5, TimeUnit.SECONDS) }
        (descendants + root).forEach { handle ->
            attempt {
                check(!handle.isAlive) { "Could not terminate process ${handle.pid()}" }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        failures.throwIfAny()
    }

    override fun shutdownOutputDrains(executor: ExecutorService) {
        executor.shutdownNow()
    }

    override fun deleteGuard(path: Path) {
        Files.deleteIfExists(path)
    }
}

private class FailureAccumulator {
    private var primary: Throwable? = null

    fun add(failure: Throwable) {
        val existing = primary
        if (existing == null) {
            primary = failure
        } else {
            attachSuppressed(existing, failure)
        }
    }

    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            add(failure)
        }
    }

    fun throwIfAny() {
        primary?.let { throw it }
    }
}

private fun attachCleanupFailure(primary: Throwable, cleanup: () -> Unit) {
    try {
        cleanup()
    } catch (failure: Throwable) {
        attachSuppressed(primary, failure)
    }
}

private fun attachSuppressed(primary: Throwable, suppressed: Throwable) {
    if (
        primary !== suppressed &&
            primary.suppressed.none { existing -> existing === suppressed }
    ) {
        primary.addSuppressed(suppressed)
    }
}

private class TailDrain(private val input: InputStream) : Callable<String> {
    override fun call(): String {
        val tail = ByteArray(OUTPUT_TAIL_BYTES)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var retained = 0
        var writeIndex = 0
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    tail[writeIndex] = buffer[index]
                    writeIndex = (writeIndex + 1) % tail.size
                    retained = minOf(retained + 1, tail.size)
                }
            }
        }
        val ordered = ByteArray(retained)
        val start = if (retained == tail.size) writeIndex else 0
        for (index in ordered.indices) ordered[index] = tail[(start + index) % tail.size]
        return ordered.toString(UTF_8)
    }
}

internal const val OUTPUT_TAIL_BYTES: Int = 64 * 1024
