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
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile

/**
 * Launches child JVMs without a shell and rejects stale or in-place result writes.
 *
 * The trusted target worker separately publishes with a same-directory atomic move.
 */
class JdkProcessLauncher : ProcessLauncher {
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
        Files.write(atomicGuard, guardBytes)
        Files.createLink(resultFile, atomicGuard)

        return try {
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
            val process =
                ProcessBuilder(arguments)
                    .directory(command.workingDirectory.toFile())
                    .start()
            val processId = process.pid()
            val drains = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory())
            val stdout = drains.submit(TailDrain(process.inputStream))
            val stderr = drains.submit(TailDrain(process.errorStream))
            try {
                val finished = waitFor(process, command.timeout)
                if (!finished) terminateProcessTree(process)
                val elapsedNanos = System.nanoTime() - startedAt
                val stdoutTail = finishDrain(stdout, process.inputStream)
                val stderrTail = finishDrain(stderr, process.errorStream)

                check(finished) {
                    "Target process $processId timed out after ${command.timeout}: " +
                        diagnostics(stdoutTail, stderrTail)
                }
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
                ProcessObservation(
                    exitCode = exitCode,
                    processId = processId,
                    elapsedNanos = elapsedNanos,
                    stdoutTail = stdoutTail,
                    stderrTail = stderrTail,
                    result = result,
                )
            } catch (failure: InterruptedException) {
                terminateProcessTree(process)
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while waiting for target process $processId", failure)
            } catch (failure: Throwable) {
                if (process.isAlive) terminateProcessTree(process)
                throw failure
            } finally {
                drains.shutdownNow()
            }
        } finally {
            Files.deleteIfExists(atomicGuard)
        }
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

    private fun terminateProcessTree(process: Process) {
        val root = process.toHandle()
        val descendants = root.descendants().use { it.toList() }
        descendants.asReversed().forEach(ProcessHandle::destroy)
        root.destroy()
        process.waitFor(1, TimeUnit.SECONDS)
        descendants.asReversed().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
        if (root.isAlive) root.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }

    private fun finishDrain(future: Future<String>, stream: InputStream): String =
        try {
            future.get(5, TimeUnit.SECONDS)
        } catch (failure: Throwable) {
            runCatching(stream::close)
            future.cancel(true)
            throw IllegalStateException("Could not drain child process output", failure)
        }

    private fun diagnostics(stdout: String, stderr: String): String =
        "stdoutTail=${stdout.ifEmpty { "<empty>" }}, stderrTail=${stderr.ifEmpty { "<empty>" }}"
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
