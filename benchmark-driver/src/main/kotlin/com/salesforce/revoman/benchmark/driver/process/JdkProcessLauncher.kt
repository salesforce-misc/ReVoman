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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
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
    private val finalizationDeadlineFactory: ProcessTreeFinalizationDeadlineFactory,
) : ProcessLauncher {
    internal constructor(cleanup: LauncherCleanup) :
        this(
            cleanup,
            DefaultOutputDrainAwaiter,
            DefaultProcessTreeTrackerFactory,
            DefaultProcessTreeFinalizationDeadlineFactory,
        )

    internal constructor(cleanup: LauncherCleanup, drainAwaiter: OutputDrainAwaiter) :
        this(
            cleanup,
            drainAwaiter,
            DefaultProcessTreeTrackerFactory,
            DefaultProcessTreeFinalizationDeadlineFactory,
        )

    internal constructor(cleanup: LauncherCleanup, trackerFactory: ProcessTreeTrackerFactory) :
        this(
            cleanup,
            DefaultOutputDrainAwaiter,
            trackerFactory,
            DefaultProcessTreeFinalizationDeadlineFactory,
        )

    internal constructor(
        cleanup: LauncherCleanup,
        trackerFactory: ProcessTreeTrackerFactory,
        finalizationDeadlineFactory: ProcessTreeFinalizationDeadlineFactory,
    ) : this(cleanup, DefaultOutputDrainAwaiter, trackerFactory, finalizationDeadlineFactory)

    constructor() :
        this(
            DefaultLauncherCleanup,
            DefaultOutputDrainAwaiter,
            DefaultProcessTreeTrackerFactory,
            DefaultProcessTreeFinalizationDeadlineFactory,
        )

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
        var launcherTasks: ExecutorService? = null
        var processTree: ProcessTreeTracking? = null
        var trackerTask: Future<*>? = null
        var finalizeOwnedProcessTree: (() -> Unit)? = null
        var trackingFrozen = false
        var observation: ProcessObservation? = null
        var finalization: OwnedProcessTreeFinalization? = null
        var finalizationDeadline: ProcessTreeFinalizationDeadline? = null
        val finalizationStarted = AtomicBoolean(false)
        val freezeProcessTree = { timeoutNanos: Long ->
            if (!trackingFrozen) {
                processTree?.stopSampling()
                trackerTask?.get(maxOf(0L, timeoutNanos), TimeUnit.NANOSECONDS)
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
            launcherTasks = launcherTaskExecutor()
            val startedAt = System.nanoTime()
            process =
                ProcessBuilder(arguments)
                    .directory(command.workingDirectory.toFile())
                    .start()
            val launchedProcess = process
            val finalizeAction = {
                if (finalizationStarted.compareAndSet(false, true)) {
                    try {
                        val deadline =
                            finalizationDeadlineFactory.create().also { created ->
                                finalizationDeadline = created
                            }
                        finalization =
                            cleanup.finalizeOwnedProcessTree(
                                process = launchedProcess,
                                retainedDescendants = {
                                    processTree?.snapshot()?.descendants.orEmpty()
                                },
                                freezeTracking = freezeProcessTree,
                                deadline = deadline,
                            )
                    } finally {
                        processTree?.stopSampling()
                    }
                }
            }
            finalizeOwnedProcessTree = finalizeAction
            val processId = launchedProcess.pid()
            val executor = requireNotNull(launcherTasks)
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
                    processTree = trackedTree,
                    finalizeOwnedProcessTree = finalizeAction,
                )
        } catch (failure: Throwable) {
            failures.add(failure)
        } finally {
            if (process != null) {
                failures.attempt {
                    requireNotNull(finalizeOwnedProcessTree) {
                            "Target process has no owned-tree finalization action"
                        }
                        .invoke()
                }
            }
            val continuationRequired = process != null && finalizationStarted.get() && !trackingFrozen
            var continuationCompleted = false
            val continueFinalizationSweep = {
                if (!continuationCompleted) {
                    val continued =
                        cleanup.continueOwnedProcessTreeFinalization(
                            process = requireNotNull(process),
                            retainedDescendants = {
                                processTree?.snapshot()?.descendants.orEmpty()
                            },
                        )
                    finalization =
                        OwnedProcessTreeFinalization(
                            hadLiveDescendants =
                                finalization?.hadLiveDescendants == true ||
                                    continued.hadLiveDescendants
                        )
                    continuationCompleted = true
                }
            }
            failures.attempt {
                freezeProcessTree(finalizationDeadline?.remainingNanos() ?: 0L)
            }
            if (continuationRequired && trackingFrozen) {
                failures.attempt(continueFinalizationSweep)
            }
            val captureProcessTreeTrackingFailure = {
                processTree?.failureOrNull()?.let { failure ->
                    if (!failures.contains(failure)) {
                        failures.add(processTreeTrackingFailure(failure))
                    }
                }
            }
            captureProcessTreeTrackingFailure()
            var descendantIsolationReported = false
            val reportDescendantIsolation = {
                if (
                    !descendantIsolationReported &&
                        observation != null &&
                        finalization?.hadLiveDescendants == true
                ) {
                    failures.add(
                        IllegalStateException(
                            "Target process ${observation.processId} left a live descendant; " +
                                "the owned process tree was finalized and the sample is invalid"
                        )
                    )
                    descendantIsolationReported = true
                }
            }
            reportDescendantIsolation()
            launcherTasks?.let { executor ->
                failures.attempt {
                    cleanup.shutdownLauncherTasks(executor)
                }
            }
            if (continuationRequired && !trackingFrozen) {
                failures.attempt(continueFinalizationSweep)
                reportDescendantIsolation()
            }
            captureProcessTreeTrackingFailure()
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
        processTree: ProcessTreeTracking,
        finalizeOwnedProcessTree: () -> Unit,
    ): ProcessObservation {
        val waitOutcome =
            try {
                waitForProcessOrTrackingFailure(process, processTree, timeout)
            } catch (failure: InterruptedException) {
                val interrupted =
                    InterruptedLauncherFailure(
                        "Interrupted while waiting for target process $processId",
                        failure,
                    )
                attachCleanupFailure(interrupted, finalizeOwnedProcessTree)
                throw interrupted
            }
        if (waitOutcome == null) {
            val timeoutFailure =
                IllegalStateException("Target process $processId timed out after $timeout")
            attachCleanupFailure(timeoutFailure, finalizeOwnedProcessTree)
            throw timeoutFailure
        }
        if (waitOutcome is ProcessWaitOutcome.TrackingFailed) {
            throw processTreeTrackingFailure(waitOutcome.failure)
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

    private fun waitForProcessOrTrackingFailure(
        process: Process,
        processTree: ProcessTreeTracking,
        timeout: Duration,
    ): ProcessWaitOutcome? =
        try {
            val processExit =
                process.onExit().thenApply<ProcessWaitOutcome> { ProcessWaitOutcome.RootExited }
            val trackingFailure =
                processTree.failureSignal().thenApply<ProcessWaitOutcome> { failure ->
                    ProcessWaitOutcome.TrackingFailed(failure)
                }
            CompletableFuture.anyOf(processExit, trackingFailure)
                .get(timeout.toNanos(), TimeUnit.NANOSECONDS) as ProcessWaitOutcome
        } catch (_: TimeoutException) {
            null
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("Java timeout is too large: $timeout", failure)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
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

internal fun interface MonotonicClock {
    fun nanoTime(): Long
}

private object SystemMonotonicClock : MonotonicClock {
    override fun nanoTime(): Long = System.nanoTime()
}

internal data class ProcessTreeFinalizationBudget(
    val totalNanos: Long,
    val trackerJoinNanos: Long,
    val postFreezeNanos: Long,
) {
    init {
        require(totalNanos > 0) { "Process-tree finalization total budget must be positive" }
        require(trackerJoinNanos >= 0) { "Process-tree tracker-join budget must not be negative" }
        require(postFreezeNanos >= 0) { "Process-tree post-freeze budget must not be negative" }
        require(trackerJoinNanos <= totalNanos - postFreezeNanos) {
            "Process-tree tracker-join and post-freeze budgets exceed the total budget"
        }
    }
}

internal class ProcessTreeFinalizationDeadline private constructor(
    private val clock: MonotonicClock,
    private val budget: ProcessTreeFinalizationBudget,
    startedAtNanos: Long,
) {
    internal val deadlineNanos = startedAtNanos + budget.totalNanos
    internal val preFreezeDeadlineNanos =
        deadlineNanos - budget.trackerJoinNanos - budget.postFreezeNanos
    private val trackerJoinDeadlineNanos = deadlineNanos - budget.postFreezeNanos

    fun nanoTime(): Long = clock.nanoTime()

    fun remainingUntil(targetNanos: Long): Long = maxOf(0L, targetNanos - nanoTime())

    fun remainingForTrackerJoin(): Long =
        minOf(budget.trackerJoinNanos, remainingUntil(trackerJoinDeadlineNanos))

    fun remainingNanos(): Long = remainingUntil(deadlineNanos)

    fun cappedDeadlineNanos(maxDurationNanos: Long, capNanos: Long): Long {
        require(maxDurationNanos >= 0) { "Capped deadline duration must not be negative" }
        val now = nanoTime()
        return now + minOf(maxDurationNanos, maxOf(0L, capNanos - now))
    }

    companion object {
        fun start(
            clock: MonotonicClock = SystemMonotonicClock,
            budget: ProcessTreeFinalizationBudget = DEFAULT_PROCESS_TREE_FINALIZATION_BUDGET,
        ): ProcessTreeFinalizationDeadline =
            ProcessTreeFinalizationDeadline(clock, budget, clock.nanoTime())
    }
}

internal fun interface ProcessTreeFinalizationDeadlineFactory {
    fun create(): ProcessTreeFinalizationDeadline
}

private object DefaultProcessTreeFinalizationDeadlineFactory :
    ProcessTreeFinalizationDeadlineFactory {
    override fun create(): ProcessTreeFinalizationDeadline = ProcessTreeFinalizationDeadline.start()
}

internal interface LauncherCleanup {
    fun finalizeOwnedProcessTree(
        process: Process,
        retainedDescendants: () -> List<ProcessHandle> = { emptyList() },
        freezeTracking: (Long) -> Unit = {},
        deadline: ProcessTreeFinalizationDeadline = ProcessTreeFinalizationDeadline.start(),
    ): OwnedProcessTreeFinalization

    fun continueOwnedProcessTreeFinalization(
        process: Process,
        retainedDescendants: () -> List<ProcessHandle>,
    ): OwnedProcessTreeFinalization

    fun shutdownLauncherTasks(executor: ExecutorService)

    fun deleteGuard(path: Path)
}

internal data class OwnedProcessTreeFinalization(val hadLiveDescendants: Boolean)

/**
 * Raw identity slots for the one possible abandoned observation at each bounded settle phase:
 * graceful, pre-freeze forcible, and post-freeze forcible. No handle hash or equality is invoked.
 */
internal class DeferredObservedProcessHandles(
    capacity: Int = MAX_DEFERRED_OBSERVED_HANDLES,
) {
    private val slots = arrayOfNulls<ProcessHandle>(capacity)
    private var retained = 0

    init {
        require(capacity > 0) { "Deferred-observed process-handle capacity must be positive" }
    }

    fun retain(handle: ProcessHandle, onOverflow: (ProcessHandle) -> Unit) {
        if (retained < slots.size) {
            slots[retained] = handle
            retained += 1
        } else {
            onOverflow(handle)
        }
    }

    fun drain(): List<ProcessHandle> {
        val drained = List(retained) { index -> requireNotNull(slots[index]) }
        repeat(retained) { index -> slots[index] = null }
        retained = 0
        return drained
    }
}

internal object DefaultLauncherCleanup : LauncherCleanup {
    override fun finalizeOwnedProcessTree(
        process: Process,
        retainedDescendants: () -> List<ProcessHandle>,
        freezeTracking: (Long) -> Unit,
        deadline: ProcessTreeFinalizationDeadline,
    ): OwnedProcessTreeFinalization {
        val failures = FailureAccumulator()
        val ownedTree = OwnedProcessTreeState(process, retainedDescendants, failures)

        val gracefulDeadline =
            deadline.cappedDeadlineNanos(
                maxDurationNanos = PROCESS_TREE_GRACE_NANOS,
                capNanos = deadline.preFreezeDeadlineNanos,
            )
        val settledGracefully = ownedTree.settle(deadline, gracefulDeadline, forcibly = false)
        if (!settledGracefully) {
            ownedTree.settle(deadline, deadline.preFreezeDeadlineNanos, forcibly = true)
        }
        failures.attempt { freezeTracking(deadline.remainingForTrackerJoin()) }
        ownedTree.settle(deadline, deadline.deadlineNanos, forcibly = true)
        ownedTree.forceAndReportFinalSnapshot()
        return ownedTree.result()
    }

    override fun continueOwnedProcessTreeFinalization(
        process: Process,
        retainedDescendants: () -> List<ProcessHandle>,
    ): OwnedProcessTreeFinalization {
        val ownedTree = OwnedProcessTreeState(process, retainedDescendants, FailureAccumulator())
        ownedTree.forceAndReportFinalSnapshot()
        return ownedTree.result()
    }

    override fun shutdownLauncherTasks(executor: ExecutorService) {
        executor.shutdownNow()
        check(executor.awaitTermination(LAUNCHER_TASK_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
            "Could not stop launcher background tasks"
        }
    }

    override fun deleteGuard(path: Path) {
        Files.deleteIfExists(path)
    }
}

private class OwnedProcessTreeState(
    private val process: Process,
    private val retainedDescendants: () -> List<ProcessHandle>,
    private val failures: FailureAccumulator,
) {
    private var root: ProcessHandle? = null
    val knownDescendants = linkedSetOf<ProcessHandle>()
    private var hadLiveDescendants = false
    private var finalizationOverflowReported = false
    private val deferredObservedHandles = DeferredObservedProcessHandles()

    init {
        attempt { root = process.toHandle() }
    }

    fun settle(
        deadline: ProcessTreeFinalizationDeadline,
        phaseDeadline: Long,
        forcibly: Boolean,
    ): Boolean {
        val phase = ProcessTreeTraversalPhase(deadline, phaseDeadline)
        var stableScans = 0
        while (phase.allowsAnotherOperation()) {
            val liveDescendants = captureLiveDescendants(phase)
            if (!phase.allowsAnotherOperation()) return false
            val rootAlive = rootIsAlive()
            if (!phase.allowsAnotherOperation()) return false
            if (liveDescendants.isEmpty() && !rootAlive) {
                stableScans += 1
                if (stableScans >= REQUIRED_STABLE_PROCESS_TREE_SCANS) return true
            } else {
                stableScans = 0
                destroy(liveDescendants, forcibly, phase)
            }
            LockSupport.parkNanos(
                minOf(PROCESS_TREE_SAMPLE_NANOS, deadline.remainingUntil(phaseDeadline))
            )
        }
        return false
    }

    fun forceAndReportFinalSnapshot() {
        val finalDescendants = captureLiveDescendants(phase = null)
        destroy(finalDescendants, forcibly = true, phase = null)
        finalDescendants.forEach { handle ->
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
    }

    fun result(): OwnedProcessTreeFinalization {
        failures.throwIfAny()
        return OwnedProcessTreeFinalization(hadLiveDescendants)
    }

    private fun attempt(action: () -> Unit) = failures.attempt(action)

    private fun isAlive(handle: ProcessHandle): Boolean {
        var alive = true
        attempt { alive = handle.isAlive }
        return alive
    }

    private fun rootIsAlive(): Boolean {
        val rootHandle = root
        if (rootHandle != null) return isAlive(rootHandle)
        var alive = true
        attempt { alive = process.isAlive }
        return alive
    }

    private fun remember(handle: ProcessHandle, phase: ProcessTreeTraversalPhase?): Boolean {
        fun allowsAnotherOperation(): Boolean = phase?.allowsAnotherOperation() != false

        hadLiveDescendants = true
        if (!allowsAnotherOperation()) {
            deferObserved(handle)
            return false
        }
        val alreadyKnown = handle in knownDescendants
        if (alreadyKnown) return true
        if (!allowsAnotherOperation()) {
            deferObserved(handle)
            return false
        }
        val hasCapacity = knownDescendants.size < MAX_TRACKED_DESCENDANTS
        if (hasCapacity) {
            if (!allowsAnotherOperation()) {
                deferObserved(handle)
                return false
            }
            knownDescendants += handle
            return true
        } else {
            if (!finalizationOverflowReported) {
                finalizationOverflowReported = true
                failures.add(
                    IllegalStateException(
                        "Target process tree finalization exceeded " +
                            "$MAX_TRACKED_DESCENDANTS live descendants"
                    )
                )
            }
            if (!allowsAnotherOperation()) {
                deferObserved(handle)
                return false
            }
            attempt { handle.destroyForcibly() }
            return false
        }
    }

    private fun deferObserved(handle: ProcessHandle) {
        deferredObservedHandles.retain(handle) { overflow ->
            failures.add(
                IllegalStateException(
                    "Target process tree finalization exceeded " +
                        "$MAX_DEFERRED_OBSERVED_HANDLES deferred observed handles"
                )
            )
            attempt { overflow.destroyForcibly() }
        }
    }

    private fun captureLiveDescendants(phase: ProcessTreeTraversalPhase?): List<ProcessHandle> {
        fun allowsAnotherOperation(): Boolean = phase?.allowsAnotherOperation() != false

        val deferredNotRetained =
            if (phase == null) {
                deferredObservedHandles.drain().filterNot { handle ->
                    remember(handle, phase = null)
                }
            } else {
                emptyList()
            }
        var retained = emptyList<ProcessHandle>()
        if (allowsAnotherOperation()) attempt { retained = retainedDescendants() }
        retained
            .asSequence()
            .takeWhile { allowsAnotherOperation() }
            .filter(::isAlive)
            .takeWhile { allowsAnotherOperation() }
            .forEach { handle -> remember(handle, phase) }
        val liveRetained =
            knownDescendants
                .asSequence()
                .takeWhile { allowsAnotherOperation() }
                .filter(::isAlive)
                .takeWhile { allowsAnotherOperation() }
                .toList()
        buildList {
                root?.let(::add)
                addAll(liveRetained)
            }
            .asSequence()
            .takeWhile { allowsAnotherOperation() }
            .distinct()
            .forEach { anchor ->
                if (!allowsAnotherOperation()) return@forEach
                val rootAnchor = anchor == root
                if (!allowsAnotherOperation()) return@forEach
                val liveAnchor =
                    when {
                        rootAnchor -> true
                        !allowsAnotherOperation() -> return@forEach
                        else -> isAlive(anchor)
                    }
                if (liveAnchor && allowsAnotherOperation()) {
                    attempt {
                        anchor.descendants().use { handles ->
                            handles
                                .takeWhile { allowsAnotherOperation() }
                                .filter(::isAlive)
                                .takeWhile { allowsAnotherOperation() }
                                .forEach { handle -> remember(handle, phase) }
                        }
                    }
                }
            }
        val liveKnownDescendants =
            knownDescendants
                .asSequence()
                .takeWhile { allowsAnotherOperation() }
                .filter(::isAlive)
                .takeWhile { allowsAnotherOperation() }
                .toList()
        return deferredNotRetained.asSequence().filter(::isAlive).toList() + liveKnownDescendants
    }

    private fun destroy(
        liveDescendants: List<ProcessHandle>,
        forcibly: Boolean,
        phase: ProcessTreeTraversalPhase?,
    ) {
        fun allowsAnotherOperation(): Boolean = phase?.allowsAnotherOperation() != false

        liveDescendants
            .asReversed()
            .asSequence()
            .takeWhile { allowsAnotherOperation() }
            .forEach { descendant ->
                attempt {
                    if (forcibly) descendant.destroyForcibly() else descendant.destroy()
                }
            }
        val rootAlive = allowsAnotherOperation() && rootIsAlive()
        if (rootAlive && allowsAnotherOperation()) {
            root?.let { rootHandle ->
                attempt {
                    if (forcibly) rootHandle.destroyForcibly() else rootHandle.destroy()
                }
            } ?: attempt {
                if (forcibly) process.destroyForcibly() else process.destroy()
            }
        }
    }
}

/**
 * Stops repeated traversal work at one finalization phase boundary.
 *
 * A retained snapshot or JDK [ProcessHandle] call already in progress cannot be preempted. Once it
 * returns, the next retained-handle, known-handle, anchor, descendant, or destruction operation is
 * not started when that call consumed the phase's remaining time.
 */
private class ProcessTreeTraversalPhase(
    private val deadline: ProcessTreeFinalizationDeadline,
    private val phaseDeadlineNanos: Long,
) {
    fun allowsAnotherOperation(): Boolean = deadline.remainingUntil(phaseDeadlineNanos) > 0
}

/**
 * Portable best-effort descendant retention for a single launch.
 *
 * One bounded daemon task recursively samples the root and retained live handles until explicit
 * freeze, without delaying the launch thread. The nominal cadence is 10 ms, but scheduler delays
 * can make the interval longer. A descendant that spawns and reparents entirely between samples
 * cannot be discovered without platform-specific process-group, job-object, or cgroup containment.
 */
internal interface ProcessTreeTracking : Runnable {
    fun stopSampling()

    fun failureOrNull(): Throwable?

    fun failureSignal(): CompletableFuture<Throwable>

    fun snapshot(): ProcessTreeSnapshot
}

internal class ProcessTreeTracker(
    private val root: ProcessHandle,
    private val maxTrackedDescendants: Int = MAX_TRACKED_DESCENDANTS,
) : ProcessTreeTracking {
    private val sampling = AtomicBoolean(true)
    private val failure = AtomicReference<Throwable?>()
    private val failureSignal = CompletableFuture<Throwable>()
    private val runner = AtomicReference<Thread?>()
    private val descendants = ConcurrentHashMap<ProcessIdentity, ProcessHandle>()

    override fun run() {
        runner.set(Thread.currentThread())
        try {
            while (sampling.get() && !Thread.currentThread().isInterrupted && failure.get() == null) {
                if (!captureSafely()) return
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

    override fun failureSignal(): CompletableFuture<Throwable> = failureSignal

    override fun snapshot(): ProcessTreeSnapshot =
        ProcessTreeSnapshot(
            descendants = descendants.values.toList(),
        )

    private fun captureSafely(): Boolean =
        try {
            val retainedLiveHandles =
                descendants.values.asSequence().filter(ProcessHandle::isAlive).toList()
            (sequenceOf(root) + retainedLiveHandles.asSequence()).distinct().forEach { anchor ->
                if (anchor == root || anchor.isAlive) {
                    anchor.descendants().use { handles ->
                        handles.forEach { handle ->
                            if (handle.isAlive) retain(handle)
                        }
                    }
                }
            }
            descendants.entries.removeIf { (_, handle) -> !handle.isAlive }
            true
        } catch (caught: Throwable) {
            if (failure.compareAndSet(null, caught)) failureSignal.complete(caught)
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

private sealed interface ProcessWaitOutcome {
    data object RootExited : ProcessWaitOutcome

    data class TrackingFailed(val failure: Throwable) : ProcessWaitOutcome
}

private fun processTreeTrackingFailure(failure: Throwable): IllegalStateException =
    IllegalStateException("Could not track target process descendants", failure)

internal class FailureAccumulator {
    private var primary: Throwable? = null
    private var interrupted = false
    private var suppressionOverflowReported = false

    fun add(failure: Throwable, interrupted: Boolean = failure is InterruptedLauncherFailure) {
        captureInterruption(interrupted)
        val existing = primary
        if (existing == null) {
            primary = failure
        } else if (!contains(failure)) {
            if (existing.suppressed.size < MAX_SUPPRESSED_FAILURES) {
                attachSuppressed(existing, failure)
            } else if (!suppressionOverflowReported) {
                suppressionOverflowReported = true
                existing.addSuppressed(
                    IllegalStateException(
                        "Additional launcher failures omitted after " +
                            "$MAX_SUPPRESSED_FAILURES diagnostics"
                    )
                )
            }
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

    fun contains(failure: Throwable): Boolean {
        fun Throwable.containsIdentity(): Boolean =
            this === failure || cause?.containsIdentity() == true || suppressed.any { it.containsIdentity() }
        return primary?.containsIdentity() == true
    }

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
private const val PROCESS_TREE_GRACE_NANOS: Long = 250_000_000
private val DEFAULT_PROCESS_TREE_FINALIZATION_BUDGET =
    ProcessTreeFinalizationBudget(
        totalNanos = 5_000_000_000,
        trackerJoinNanos = 500_000_000,
        postFreezeNanos = 500_000_000,
    )
private const val REQUIRED_STABLE_PROCESS_TREE_SCANS: Int = 2
private const val MAX_TRACKED_DESCENDANTS: Int = 4_096
private const val MAX_DEFERRED_OBSERVED_HANDLES: Int = 3
private const val MAX_SUPPRESSED_FAILURES: Int = 128
