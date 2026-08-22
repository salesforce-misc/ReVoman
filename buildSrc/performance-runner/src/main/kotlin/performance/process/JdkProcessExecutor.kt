/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.process

import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicReference

internal data class ProcessExecutorHooks(val beforeProcessStart: () -> Unit = {})

/** Executes an already validated absolute JDK command without a shell. */
internal class JdkProcessExecutor internal constructor(
  private val hooks: ProcessExecutorHooks,
) : ProcessExecutor {
  constructor() : this(ProcessExecutorHooks())

  override fun execute(spec: ProcessInvocation): ProcessResult {
    Files.createDirectories(spec.stdoutPath.parent)
    Files.createDirectories(spec.stderrPath.parent)
    FileChannel.open(spec.stdoutPath, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { stdout ->
      FileChannel.open(spec.stderrPath, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { stderr ->
        hooks.beforeProcessStart()
        val processBuilder =
          ProcessBuilder(listOf(spec.executable.toString()) + spec.arguments)
            .directory(spec.workingDirectory.toFile())
        processBuilder.environment().apply {
          clear()
          putAll(spec.environment)
        }
        val process = processBuilder.start()
        val failure = AtomicReference<Throwable?>()
        val stdoutPump = pump("performance-stdout", process.inputStream, stdout, process, failure)
        val stderrPump = pump("performance-stderr", process.errorStream, stderr, process, failure)
        val exitCode = waitFor(process, stdoutPump, stderrPump)
        failure.get()?.let { throw IOException("failed to capture child output", it) }
        stdout.force(true)
        stderr.force(true)
        return ProcessResult(exitCode)
      }
    }
  }

  private fun pump(
    name: String,
    input: InputStream,
    output: FileChannel,
    process: Process,
    failure: AtomicReference<Throwable?>,
  ): Thread =
    Thread.ofPlatform()
      .name(name)
      .unstarted {
        try {
          input.use { source ->
            val sink = Channels.newOutputStream(output)
            source.copyTo(sink)
            sink.flush()
          }
        } catch (problem: Throwable) {
          failure.compareAndSet(null, problem)
          process.destroyForcibly()
        }
      }
      .also(Thread::start)

  private fun waitFor(process: Process, vararg pumps: Thread): Int =
    try {
      val exitCode = process.waitFor()
      pumps.forEach(Thread::join)
      exitCode
    } catch (interrupted: InterruptedException) {
      process.destroyForcibly()
      Thread.currentThread().interrupt()
      throw IOException("interrupted while capturing child output", interrupted)
    }
}
