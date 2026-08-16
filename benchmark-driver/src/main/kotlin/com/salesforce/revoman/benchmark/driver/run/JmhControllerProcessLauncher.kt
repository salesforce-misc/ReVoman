/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.salesforce.revoman.benchmark.driver.process.DefaultLauncherCleanup
import com.salesforce.revoman.benchmark.driver.process.FailureAccumulator
import com.salesforce.revoman.benchmark.driver.process.JmhControllerObservation
import com.salesforce.revoman.benchmark.driver.process.LauncherCleanup
import com.salesforce.revoman.benchmark.driver.process.OutputDrainAwaiter
import com.salesforce.revoman.benchmark.driver.process.OwnedProcessTreeFinalization
import com.salesforce.revoman.benchmark.driver.process.ProcessTreeFinalizationDeadline
import com.salesforce.revoman.benchmark.driver.process.ProcessTreeFinalizationDeadlineFactory
import com.salesforce.revoman.benchmark.driver.process.ProcessTreeTracker
import com.salesforce.revoman.benchmark.driver.process.ProcessTreeTrackerFactory
import com.salesforce.revoman.benchmark.driver.process.ProcessTreeTracking
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Starts one already-tokenized JMH controller command. */
internal fun interface JmhControllerProcessStarter {
    fun start(arguments: List<String>, workingDirectory: Path): Process
}

/** Shell-free lifecycle-safe launcher for one strict JMH controller process. */
class JmhControllerProcessLauncher
internal constructor(
    private val cleanup: LauncherCleanup,
    private val drainAwaiter: OutputDrainAwaiter,
    private val trackerFactory: ProcessTreeTrackerFactory,
    private val finalizationDeadlineFactory: ProcessTreeFinalizationDeadlineFactory,
    private val processStarter: JmhControllerProcessStarter,
    private val executorFactory: () -> ExecutorService,
) : WarmAllocationLauncher {
    internal constructor(
        cleanup: LauncherCleanup,
        drainAwaiter: OutputDrainAwaiter,
        trackerFactory: ProcessTreeTrackerFactory,
        processStarter: JmhControllerProcessStarter,
    ) : this(
        cleanup = cleanup,
        drainAwaiter = drainAwaiter,
        trackerFactory = trackerFactory,
        finalizationDeadlineFactory =
            ProcessTreeFinalizationDeadlineFactory { ProcessTreeFinalizationDeadline.start() },
        processStarter = processStarter,
        executorFactory = ::jmhControllerTaskExecutor,
    )

    constructor() :
        this(
            cleanup = DefaultLauncherCleanup,
            drainAwaiter = OutputDrainAwaiter { future ->
                future.get(JMH_CONTROLLER_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            },
            trackerFactory = ProcessTreeTrackerFactory(::ProcessTreeTracker),
            finalizationDeadlineFactory =
                ProcessTreeFinalizationDeadlineFactory { ProcessTreeFinalizationDeadline.start() },
            processStarter =
                JmhControllerProcessStarter { arguments, workingDirectory ->
                    ProcessBuilder(arguments).directory(workingDirectory.toFile()).start()
                },
            executorFactory = ::jmhControllerTaskExecutor,
        )

    override fun launch(request: WarmAllocationLaunch): JmhControllerObservation {
        val command = request.command
        validateCommand(command)
        val arguments =
            buildList {
                addAll(command.invocationPrefix)
                add(command.executable.toString())
                addAll(command.jvmArgs)
                add("-cp")
                add(command.classpath.joinToString(System.getProperty("path.separator")))
                add(command.mainClass)
                addAll(command.programArgs)
            }
        val failures = FailureAccumulator()
        var process: Process? = null
        var launcherTasks: ExecutorService? = null
        var processTree: ProcessTreeTracking? = null
        var trackerTask: Future<*>? = null
        var trackingFrozen = false
        var finalization: OwnedProcessTreeFinalization? = null
        var finalizationDeadline: ProcessTreeFinalizationDeadline? = null
        var observation: JmhControllerObservation? = null
        val finalizationStarted = AtomicBoolean(false)
        val freezeProcessTree = { timeoutNanos: Long ->
            if (!trackingFrozen) {
                processTree?.stopSampling()
                trackerTask?.get(maxOf(0L, timeoutNanos), TimeUnit.NANOSECONDS)
                trackingFrozen = true
            }
        }
        try {
            launcherTasks = executorFactory()
            process = processStarter.start(arguments, command.workingDirectory)
            val launchedProcess = process
            val trackedTree = trackerFactory.create(launchedProcess.toHandle())
            processTree = trackedTree
            val executor = requireNotNull(launcherTasks)
            trackerTask = executor.submit(trackedTree)
            val stdout = executor.submit(JmhOutputDrain(launchedProcess.inputStream))
            val stderr = executor.submit(JmhOutputDrain(launchedProcess.errorStream))
            val outcome =
                try {
                    waitForProcessOrTrackingFailure(launchedProcess, trackedTree, command.timeout)
                } catch (failure: InterruptedException) {
                    throw InterruptedJmhControllerFailure(
                        "Interrupted while waiting for JMH controller ${launchedProcess.pid()}",
                        failure,
                    )
                }
            when (outcome) {
                null ->
                    error(
                        "JMH controller ${launchedProcess.pid()} timed out after ${command.timeout}"
                    )
                is JmhControllerWaitOutcome.TrackingFailed ->
                    throw jmhControllerTrackingFailure(outcome.failure)
                JmhControllerWaitOutcome.RootExited -> Unit
            }
            val stdoutTail = finishDrain(stdout, launchedProcess.inputStream)
            val stderrTail = finishDrain(stderr, launchedProcess.errorStream)
            observation =
                JmhControllerObservation(
                    exitCode = launchedProcess.exitValue(),
                    processId = launchedProcess.pid(),
                    stdoutTail = stdoutTail,
                    stderrTail = stderrTail,
                )
        } catch (failure: Throwable) {
            failures.add(failure, interrupted = failure is InterruptedJmhControllerFailure)
        } finally {
            process?.let { launchedProcess ->
                failures.attempt {
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
            }
            val continuationRequired =
                process != null && finalizationStarted.get() && !trackingFrozen
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
            val captureTrackingFailure = {
                processTree?.failureOrNull()?.let { failure ->
                    if (!failures.contains(failure)) {
                        failures.add(jmhControllerTrackingFailure(failure))
                    }
                }
            }
            captureTrackingFailure()
            if (observation != null && finalization?.hadLiveDescendants == true) {
                failures.add(
                    IllegalStateException(
                        "JMH controller ${observation.processId} left a live descendant; " +
                            "the owned process tree was finalized and the sample is invalid"
                    )
                )
            }
            launcherTasks?.let { executor ->
                failures.attempt { cleanup.shutdownLauncherTasks(executor) }
            }
            if (continuationRequired && !trackingFrozen) {
                failures.attempt(continueFinalizationSweep)
            }
            captureTrackingFailure()
        }
        failures.throwIfAny()
        return requireNotNull(observation) {
            "JMH controller completed without a process observation"
        }
    }

    private fun finishDrain(future: Future<String>, stream: InputStream): String =
        try {
            drainAwaiter.await(future)
        } catch (failure: Throwable) {
            val drainFailure =
                if (failure is InterruptedException) {
                    InterruptedJmhControllerFailure(
                        "Could not drain JMH controller output",
                        failure,
                    )
                } else {
                    IllegalStateException("Could not drain JMH controller output", failure)
                }
            attachJmhCleanupFailure(drainFailure, stream::close)
            attachJmhCleanupFailure(drainFailure) { future.cancel(true) }
            throw drainFailure
        }

    private fun validateCommand(command: com.salesforce.revoman.benchmark.driver.process.JavaCommand) {
        require(command.executable.isAbsolute) { "JMH controller executable must be absolute" }
        require(command.invocationPrefix.none(String::isBlank)) {
            "JMH controller invocation prefix must not contain blank arguments"
        }
        command.invocationPrefix.firstOrNull()?.let { executable ->
            require(Path.of(executable).isAbsolute) {
                "JMH controller invocation-prefix executable must be absolute: $executable"
            }
        }
        require(command.classpath.isNotEmpty() && command.classpath.all(Path::isAbsolute)) {
            "JMH controller classpath entries must be absolute"
        }
        require(command.mainClass.isNotBlank()) { "JMH controller mainClass must not be blank" }
        require(command.workingDirectory.isAbsolute && Files.isDirectory(command.workingDirectory)) {
            "JMH controller workingDirectory must be an existing absolute directory"
        }
        require(!command.timeout.isZero && !command.timeout.isNegative) {
            "JMH controller timeout must be positive"
        }
    }
}

private fun waitForProcessOrTrackingFailure(
    process: Process,
    processTree: ProcessTreeTracking,
    timeout: Duration,
): JmhControllerWaitOutcome? =
    try {
        val processExit =
            process.onExit().thenApply<JmhControllerWaitOutcome> {
                JmhControllerWaitOutcome.RootExited
            }
        val trackingFailure =
            processTree.failureSignal().thenApply<JmhControllerWaitOutcome> { failure ->
                JmhControllerWaitOutcome.TrackingFailed(failure)
            }
        CompletableFuture.anyOf(processExit, trackingFailure)
            .get(timeout.toNanos(), TimeUnit.NANOSECONDS) as JmhControllerWaitOutcome
    } catch (_: TimeoutException) {
        null
    } catch (failure: ArithmeticException) {
        throw IllegalArgumentException("JMH controller timeout is too large: $timeout", failure)
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    }

private sealed interface JmhControllerWaitOutcome {
    data object RootExited : JmhControllerWaitOutcome

    data class TrackingFailed(val failure: Throwable) : JmhControllerWaitOutcome
}

private fun jmhControllerTrackingFailure(failure: Throwable): IllegalStateException =
    IllegalStateException("Could not track JMH controller descendants", failure)

private class InterruptedJmhControllerFailure(message: String, cause: InterruptedException) :
    IllegalStateException(message, cause)

private fun attachJmhCleanupFailure(primary: Throwable, cleanup: () -> Unit) {
    try {
        cleanup()
    } catch (failure: Throwable) {
        if (primary !== failure && primary.suppressed.none { existing -> existing === failure }) {
            primary.addSuppressed(failure)
        }
    }
}

private class JmhOutputDrain(private val input: InputStream) : Callable<String> {
    override fun call(): String {
        val tail = ByteArray(JMH_CONTROLLER_OUTPUT_TAIL_BYTES)
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
        return String(ordered, UTF_8)
    }
}

private fun jmhControllerTaskExecutor(): ExecutorService =
    (Executors.newFixedThreadPool(
            JMH_CONTROLLER_TASK_COUNT,
            Thread.ofPlatform()
                .daemon()
                .name(JMH_CONTROLLER_LAUNCHER_THREAD_PREFIX, 0)
                .factory(),
        ) as ThreadPoolExecutor)
        .also(ThreadPoolExecutor::prestartAllCoreThreads)

internal const val JMH_CONTROLLER_LAUNCHER_THREAD_PREFIX: String =
    "revoman-jmh-controller-launch-"
private const val JMH_CONTROLLER_OUTPUT_TAIL_BYTES: Int = 64 * 1024
private const val JMH_CONTROLLER_TASK_COUNT: Int = 3
private const val JMH_CONTROLLER_TASK_TIMEOUT_SECONDS: Long = 5
