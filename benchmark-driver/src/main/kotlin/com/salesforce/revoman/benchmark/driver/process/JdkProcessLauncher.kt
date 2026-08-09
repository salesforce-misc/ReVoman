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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.isRegularFile

/**
 * Launches child JVMs without a shell and rejects stale or in-place result writes.
 *
 * The trusted target worker separately publishes with a same-directory atomic move.
 */
class JdkProcessLauncher
internal constructor(
    private val cleanup: LauncherCleanup,
    private val drainAwaiter: OutputDrainAwaiter,
    private val trackerFactory: ProcessTreeTrackerFactory,
) : ProcessLauncher {
    internal constructor(cleanup: LauncherCleanup) :
        this(cleanup, DefaultOutputDrainAwaiter, DefaultProcessTreeTrackerFactory)

    internal constructor(cleanup: LauncherCleanup, drainAwaiter: OutputDrainAwaiter) :
        this(cleanup, drainAwaiter, DefaultProcessTreeTrackerFactory)

    internal constructor(cleanup: LauncherCleanup, trackerFactory: ProcessTreeTrackerFactory) :
        this(cleanup, DefaultOutputDrainAwaiter, trackerFactory)

    constructor() :
        this(DefaultLauncherCleanup, DefaultOutputDrainAwaiter, DefaultProcessTreeTrackerFactory)

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
        var processTree: ProcessTreeTracking? = null
        var trackerTask: Future<*>? = null
        var terminateProcessTree: (() -> Unit)? = null
        var trackingFrozen = false
        var observation: ProcessObservation? = null
        var terminationAttempted = false
        val freezeProcessTree = {
            if (!trackingFrozen) {
                processTree?.stopSampling()
                trackerTask?.get(LAUNCHER_TASK_SHUTDOWN_SECONDS, TimeUnit.SECONDS)
                trackingFrozen = true
            }
        }
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
            drains = launcherTaskExecutor()
            val startedAt = System.nanoTime()
            process =
                ProcessBuilder(arguments)
                    .directory(command.workingDirectory.toFile())
                    .start()
            val launchedProcess = process
            val terminate = {
                terminationAttempted = true
                try {
                    cleanup.terminateProcessTree(
                        process = launchedProcess,
                        retainedDescendants = {
                            processTree?.snapshot()?.descendants.orEmpty()
                        },
                        freezeTracking = freezeProcessTree,
                    )
                } finally {
                    processTree?.stopSampling()
                }
            }
            terminateProcessTree = terminate
            val processId = launchedProcess.pid()
            val executor = requireNotNull(drains)
            val trackedTree = trackerFactory.create(launchedProcess.toHandle())
            processTree = trackedTree
            trackerTask = executor.submit(trackedTree)
            val stdout = executor.submit(TailDrain(launchedProcess.inputStream))
            val stderr = executor.submit(TailDrain(launchedProcess.errorStream))
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
                    stopTracking = trackedTree::stopSampling,
                    terminate = terminate,
                )
        } catch (failure: Throwable) {
            failures.add(failure)
        } finally {
            val bodyFailed = observation == null
            if (bodyFailed && !terminationAttempted && process != null) {
                failures.attempt {
                    requireNotNull(terminateProcessTree) { "Target process has no termination action" }
                        .invoke()
                }
            }
            failures.attempt(freezeProcessTree)
            processTree?.failureOrNull()?.let { failure ->
                failures.add(IllegalStateException("Could not track target process descendants", failure))
            }
            if (!bodyFailed && !terminationAttempted && process != null && failures.hasFailure()) {
                failures.attempt {
                    requireNotNull(terminateProcessTree) { "Target process has no termination action" }
                        .invoke()
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
        stopTracking: () -> Unit,
        terminate: () -> Unit,
    ): ProcessObservation {
        val finished =
            try {
                waitFor(process, timeout)
            } catch (failure: InterruptedException) {
                val interrupted =
                    InterruptedLauncherFailure(
                        "Interrupted while waiting for target process $processId",
                        failure,
                    )
                attachCleanupFailure(interrupted, terminate)
                throw interrupted
            }
        if (!finished) {
            val timeoutFailure =
                IllegalStateException("Target process $processId timed out after $timeout")
            attachCleanupFailure(timeoutFailure, terminate)
            throw timeoutFailure
        }
        val elapsedNanos = System.nanoTime() - startedAt
        stopTracking()
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
            drainAwaiter.await(future)
        } catch (failure: Throwable) {
            val drainFailure =
                if (failure is InterruptedException) {
                    InterruptedLauncherFailure("Could not drain child process output", failure)
                } else {
                    IllegalStateException("Could not drain child process output", failure)
                }
            attachCleanupFailure(drainFailure, stream::close)
            attachCleanupFailure(drainFailure) { future.cancel(true) }
            throw drainFailure
        }

    private fun diagnostics(stdout: String, stderr: String): String =
        "stdoutTail=${stdout.ifEmpty { "<empty>" }}, stderrTail=${stderr.ifEmpty { "<empty>" }}"

    private fun launcherTaskExecutor(): ExecutorService =
        (Executors.newFixedThreadPool(
                LAUNCHER_TASK_COUNT,
                Thread.ofPlatform().daemon().name(LAUNCHER_THREAD_PREFIX, 0).factory(),
            ) as ThreadPoolExecutor)
            .also(ThreadPoolExecutor::prestartAllCoreThreads)
}

internal fun interface OutputDrainAwaiter {
    fun await(future: Future<String>): String
}

private object DefaultOutputDrainAwaiter : OutputDrainAwaiter {
    override fun await(future: Future<String>): String = future.get(5, TimeUnit.SECONDS)
}

internal fun interface ProcessTreeTrackerFactory {
    fun create(root: ProcessHandle): ProcessTreeTracking
}

private object DefaultProcessTreeTrackerFactory : ProcessTreeTrackerFactory {
    override fun create(root: ProcessHandle): ProcessTreeTracking = ProcessTreeTracker(root)
}

internal interface LauncherCleanup {
    fun terminateProcessTree(
        process: Process,
        retainedDescendants: () -> List<ProcessHandle> = { emptyList() },
        freezeTracking: () -> Unit = {},
    )

    fun shutdownOutputDrains(executor: ExecutorService)

    fun deleteGuard(path: Path)
}

internal object DefaultLauncherCleanup : LauncherCleanup {
    override fun terminateProcessTree(
        process: Process,
        retainedDescendants: () -> List<ProcessHandle>,
        freezeTracking: () -> Unit,
    ) {
        val failures = FailureAccumulator()
        fun attempt(action: () -> Unit) = failures.attempt(action)

        var root: ProcessHandle? = null
        attempt { root = process.toHandle() }
        fun knownDescendants(): List<ProcessHandle> {
            var retained = emptyList<ProcessHandle>()
            attempt { retained = retainedDescendants() }
            var current = emptyList<ProcessHandle>()
            root?.let { rootHandle ->
                attempt { current = rootHandle.descendants().use { it.toList() } }
            }
            return (retained + current).distinct()
        }

        var descendants = knownDescendants()
        fun awaitDescendants(timeout: Long, unit: TimeUnit) {
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            descendants.forEach { descendant ->
                var alive = false
                attempt { alive = descendant.isAlive }
                if (!alive) return@forEach
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return
                try {
                    descendant.onExit().get(remaining, TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                    return
                } catch (failure: Throwable) {
                    failures.add(failure, interrupted = failure is InterruptedException)
                }
            }
        }
        descendants.asReversed().forEach { descendant -> attempt { descendant.destroy() } }
        root?.let { rootHandle -> attempt { rootHandle.destroy() } }
            ?: attempt(process::destroy)
        attempt { process.waitFor(1, TimeUnit.SECONDS) }
        awaitDescendants(1, TimeUnit.SECONDS)
        root?.let { rootHandle -> attempt { rootHandle.destroyForcibly() } }
            ?: attempt { process.destroyForcibly() }
        var rootStopped = false
        attempt { rootStopped = process.waitFor(5, TimeUnit.SECONDS) }
        root?.let { rootHandle -> attempt { rootStopped = rootStopped || !rootHandle.isAlive } }
            ?: attempt { rootStopped = rootStopped || !process.isAlive }
        if (!rootStopped) {
            failures.add(IllegalStateException("Root process remained alive after forcible termination"))
        } else {
            attempt(freezeTracking)
        }
        descendants = (descendants + knownDescendants()).distinct()
        descendants.asReversed().forEach { descendant -> attempt { descendant.destroyForcibly() } }
        awaitDescendants(5, TimeUnit.SECONDS)
        if (!rootStopped) {
            attempt(freezeTracking)
            descendants = (descendants + knownDescendants()).distinct()
            descendants.asReversed().forEach { descendant -> attempt { descendant.destroyForcibly() } }
            awaitDescendants(5, TimeUnit.SECONDS)
        }
        descendants.forEach { handle ->
            attempt {
                check(!handle.isAlive) { "Could not terminate process ${handle.pid()}" }
            }
        }
        root?.let { rootHandle ->
            attempt {
                check(!rootHandle.isAlive) { "Could not terminate process ${rootHandle.pid()}" }
            }
        } ?: attempt {
            check(!process.isAlive) { "Could not terminate root process" }
        }
        failures.throwIfAny()
    }

    override fun shutdownOutputDrains(executor: ExecutorService) {
        executor.shutdownNow()
        check(executor.awaitTermination(LAUNCHER_TASK_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
            "Could not stop launcher background tasks"
        }
    }

    override fun deleteGuard(path: Path) {
        Files.deleteIfExists(path)
    }
}

/**
 * Portable best-effort descendant retention for a single launch.
 *
 * One bounded daemon task samples only while the root is alive and never delays the launch thread.
 * A process that spawns and reparents entirely between samples cannot be discovered without
 * platform-specific process-group, job-object, or cgroup containment.
 */
internal interface ProcessTreeTracking : Runnable {
    fun stopSampling()

    fun failureOrNull(): Throwable?

    fun snapshot(): ProcessTreeSnapshot
}

internal class ProcessTreeTracker(
    private val root: ProcessHandle,
    private val maxTrackedDescendants: Int = MAX_TRACKED_DESCENDANTS,
) : ProcessTreeTracking {
    private val sampling = AtomicBoolean(true)
    private val failure = AtomicReference<Throwable?>()
    private val runner = AtomicReference<Thread?>()
    private val descendants = ConcurrentHashMap<ProcessIdentity, ProcessHandle>()

    override fun run() {
        runner.set(Thread.currentThread())
        try {
            while (sampling.get() && !Thread.currentThread().isInterrupted && failure.get() == null) {
                if (!captureSafely()) return
                val rootAlive =
                    try {
                        root.isAlive
                    } catch (caught: Throwable) {
                        failure.compareAndSet(null, caught)
                        false
                    }
                if (!rootAlive) return
                LockSupport.parkNanos(PROCESS_TREE_SAMPLE_NANOS)
            }
        } finally {
            runner.compareAndSet(Thread.currentThread(), null)
        }
    }

    override fun stopSampling() {
        sampling.set(false)
        runner.get()?.interrupt()
    }

    override fun failureOrNull(): Throwable? = failure.get()

    override fun snapshot(): ProcessTreeSnapshot =
        ProcessTreeSnapshot(
            descendants = descendants.values.toList(),
        )

    private fun captureSafely(): Boolean =
        try {
            descendants.entries.removeIf { (_, handle) -> !handle.isAlive }
            root.descendants().use { handles ->
                handles.forEach { handle ->
                    if (handle.isAlive) retain(handle)
                }
            }
            true
        } catch (caught: Throwable) {
            failure.compareAndSet(null, caught)
            false
        }

    private fun retain(handle: ProcessHandle) {
        val identity =
            ProcessIdentity(
                processId = handle.pid(),
                startInstant = handle.info().startInstant().orElse(null),
            )
        if (!descendants.containsKey(identity) && descendants.size >= maxTrackedDescendants) {
            error("Target process tree exceeded $maxTrackedDescendants live descendants")
        }
        descendants[identity] = handle
    }
}

private data class ProcessIdentity(val processId: Long, val startInstant: Instant?)

internal data class ProcessTreeSnapshot(val descendants: List<ProcessHandle>)

private class FailureAccumulator {
    private var primary: Throwable? = null
    private var interrupted = false

    fun add(failure: Throwable, interrupted: Boolean = failure is InterruptedLauncherFailure) {
        captureInterruption(interrupted)
        val existing = primary
        if (existing == null) {
            primary = failure
        } else {
            attachSuppressed(existing, failure)
        }
    }

    fun attempt(action: () -> Unit) {
        captureInterruption()
        try {
            action()
        } catch (failure: Throwable) {
            add(
                failure,
                interrupted =
                    failure is InterruptedException || failure is InterruptedLauncherFailure,
            )
        } finally {
            captureInterruption()
        }
    }

    fun throwIfAny() {
        captureInterruption()
        if (interrupted) Thread.currentThread().interrupt()
        primary?.let { throw it }
    }

    fun hasFailure(): Boolean = primary != null

    private fun captureInterruption(explicit: Boolean = false) {
        val threadInterrupted = Thread.interrupted()
        if (explicit || threadInterrupted) interrupted = true
    }
}

private class InterruptedLauncherFailure(message: String, cause: InterruptedException) :
    IllegalStateException(message, cause)

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
internal const val LAUNCHER_THREAD_PREFIX: String = "revoman-benchmark-launch-"
private const val LAUNCHER_TASK_COUNT: Int = 3
private const val LAUNCHER_TASK_SHUTDOWN_SECONDS: Long = 5
private const val PROCESS_TREE_SAMPLE_NANOS: Long = 10_000_000
private const val MAX_TRACKED_DESCENDANTS: Int = 4_096
