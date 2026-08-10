/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.salesforce.revoman.benchmark.driver.process.JmhControllerObservation
import com.salesforce.revoman.benchmark.driver.process.ProcessTreeTracker
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Shell-free lifecycle-safe launcher for one strict JMH controller process. */
class JmhControllerProcessLauncher : WarmAllocationLauncher {
    override fun launch(request: WarmAllocationLaunch): JmhControllerObservation {
        val command = request.command
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
        val process = ProcessBuilder(arguments).directory(command.workingDirectory.toFile()).start()
        val tracker = ProcessTreeTracker(process.toHandle())
        val trackerThread =
            Thread.ofPlatform().daemon().name("revoman-jmh-controller-tracker").start(tracker)
        val stdout = FutureTask { process.inputStream.readAllBytes() }
        val stderr = FutureTask { process.errorStream.readAllBytes() }
        Thread.ofVirtual().start(stdout)
        Thread.ofVirtual().start(stderr)
        val exited = process.waitFor(command.timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!exited) {
            tracker.snapshot().descendants.asReversed().forEach(ProcessHandle::destroyForcibly)
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) {
                "JMH controller did not stop after timeout"
            }
        }
        tracker.stopSampling()
        trackerThread.join(5_000)
        check(!trackerThread.isAlive) { "JMH controller process tracker did not terminate" }
        tracker.failureOrNull()?.let { failure ->
            throw IllegalStateException("JMH controller process tracking failed", failure)
        }
        val liveDescendants = tracker.snapshot().descendants.filter(ProcessHandle::isAlive)
        liveDescendants.asReversed().forEach(ProcessHandle::destroyForcibly)
        if (liveDescendants.isNotEmpty()) {
            throw IllegalStateException(
                "JMH controller ${process.pid()} left live descendants: " +
                    liveDescendants.map(ProcessHandle::pid)
            )
        }
        val stdoutBytes = stdout.get(5, TimeUnit.SECONDS)
        val stderrBytes = stderr.get(5, TimeUnit.SECONDS)
        return JmhControllerObservation(
            exitCode = process.exitValue(),
            processId = process.pid(),
            stdoutTail = String(stdoutBytes).takeLast(64 * 1024),
            stderrTail = String(stderrBytes).takeLast(64 * 1024),
        )
    }
}
