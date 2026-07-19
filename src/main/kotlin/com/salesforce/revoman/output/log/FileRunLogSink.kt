/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.BufferedWriter
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * A built-in [RunLogSink] that streams ONE run's log to `<logsDir>/<runLabel>/<timestamp>.log`,
 * live (append + flush per write, so an external `tail -f` sees it during the run), and repoints
 * `latest.log` to it on [close]. The generic file-sink policy — the file layout, the
 * self-describing banner + legend, the outcome footer, content-toggle gating, the `latest.log`
 * pointer, and the never-throw guarantee — lives HERE in the library, shared by every consumer; a
 * consumer supplies only its own data (the logs dir, a run label, a mode string, a
 * [FileRunLogConfig], and any run facts via [recordRunFact]).
 *
 * All step/line rendering delegates to [RunLogRenderer] — the single grammar source — so the file
 * and console sinks cannot drift. Best-effort: any I/O error is swallowed (logged once) so logging
 * can never fail a run; the run is the product, the log is a convenience. Single-threaded for its
 * lifetime per the [RunLogSink] contract.
 */
class FileRunLogSink
private constructor(
  private val runFile: Path,
  private val testDir: Path,
  private val runLabel: String,
  private val mode: String,
  private val startedAt: Instant,
  private val config: FileRunLogConfig,
  private val out: BufferedWriter,
) : RunLogSink {

  /** Per-run step timings (path -> summed tookMs) for the heaviest-steps table (Task 5). */
  private val stepTimings = LinkedHashMap<String, Long>()

  override fun line(level: LogLevel, message: String) {
    // libLogs gates library narration: OFF drops INFO *and* DEBUG (e.g. the "{{x}} resolved from
    // scope" flood). WARN/ERROR always pass — they are diagnostics, not narration.
    if (!config.libLogs && (level == LogLevel.INFO || level == LogLevel.DEBUG)) {
      return
    }
    write("[$level] $message\n")
  }

  override fun event(event: StepEvent) {
    // Accumulate step timings for the heaviest-steps table BEFORE any content gate.
    if (event is StepEvent.StepFinished) {
      stepTimings.merge(event.path, event.tookMs) { existing, new -> existing + new }
    }
    // Coarse runbook events render under their OWN toggle (independent of `steps`), so a reader can
    // keep the runbook tree while dropping per-request bodies (or the reverse). All grammar comes
    // from RunLogRenderer — one source shared with ConsoleRunLogSink.
    if (isCoarseRunbookEvent(event)) {
      if (config.runbook) {
        write(RunLogRenderer.render(event))
      }
      return
    }
    if (!config.steps) {
      return
    }
    write(RunLogRenderer.render(event))
  }

  private fun isCoarseRunbookEvent(event: StepEvent): Boolean =
    event is StepEvent.PhaseEntered ||
      event is StepEvent.RunbookStepStarted ||
      event is StepEvent.RunbookStepFinished ||
      event is StepEvent.RunbookContractFailed

  override fun close() {
    try {
      out.flush()
      out.close()
    } catch (e: IOException) {
      logger.warn { "FileRunLogSink close/flush failed (ignored): $e" }
    }
    repointLatest()
  }

  /**
   * Append a run-level fact as a tagged `[run] key=value` line — grep-able as `[run] key=`
   * regardless of where in the file it lands. Generic: a consumer records whatever it learns
   * mid-run (e.g. a bound org id, once setUp binds it — it cannot be in the line-1 banner). A
   * `null` [value] renders `(unset)`.
   */
  fun recordRunFact(key: String, value: String?) {
    write("[run] $key=${value ?: "(unset)"}\n")
  }

  /**
   * Write the outcome footer (the final block). Gated by the `outcome` content toggle. On a
   * failure, surfaces a one-line `error` summary for a quick scan PLUS the FULL stack trace of
   * [failure] — including its `Caused by:` / `Suppressed:` chain — so a reader can diagnose the
   * break from the log alone without rerunning. Rendered via [Throwable.printStackTrace] (not a
   * hand-rolled walk) so the cause chain and the JDK's circular-reference guard are handled
   * correctly. The trace is intentionally NOT trimmed — a truncated trace can hide the very
   * root-cause frame that explains the failure.
   */
  fun footer(passed: Boolean, failingStep: String?, failure: Throwable?) {
    if (!config.outcome) {
      return
    }
    val sb = StringBuilder()
    sb
      .append("=== OUTCOME: ")
      .append(if (passed) "PASSED" else "FAILED")
      .append(" =================================\n")
    if (!passed) {
      if (failingStep != null) {
        sb.append("failingStep  ").append(failingStep).append('\n')
      }
      if (failure != null) {
        sb.append("error        ").append(failure).append('\n')
        sb.append("--- stacktrace --------------------------------------\n")
        sb.append(stackTraceOf(failure))
      }
    }
    sb.append("=====================================================\n")
    write(sb.toString())
  }

  /** Full stack trace + cause/suppressed chain of [t], as the JVM would print it. */
  private fun stackTraceOf(t: Throwable): String {
    val sw = StringWriter()
    PrintWriter(sw).use { t.printStackTrace(it) }
    val trace = sw.toString()
    // printStackTrace already ends each line with a newline; guarantee a trailing one so the
    // closing banner sits on its own line even for an exotic Throwable override.
    return if (trace.endsWith("\n")) trace else trace + "\n"
  }

  /**
   * Banner written at file-open (line 1). Self-describing for an AI/human reading the file cold:
   * the run's identity, then a one-block legend of the line grammar that follows so the rest of the
   * file is parseable with no external doc. The legend describes exactly what [RunLogRenderer]
   * emits — it lives HERE, one file away from that grammar, so the two are edited together and
   * cannot drift. Run facts learned mid-run (e.g. a bound org id) are appended later via
   * [recordRunFact].
   */
  private fun writeBanner() {
    write(
      "=== ReVoman run =====================================\n" +
        "[run] test=$runLabel\n" +
        "[run] mode=$mode\n" +
        "[run] started=${HEADER_TIME.format(startedAt)}\n" +
        "--- legend ------------------------------------------\n" +
        "[run] ...      run-level facts (test, mode, org, started)\n" +
        "[INFO|WARN|ERROR] ...   ReVoman library narration (tee of 3prvm)\n" +
        "[ReVomanPerf] mode=.. stage=.. tookMs=..   per-stage timing\n" +
        "[ReVomanPerf] SUMMARY ...   end-of-run timing roll-up\n" +
        "┌ ◆|▶ <intent>  ⟵ consumes   ★ UNDER TEST   runbook step opens (heavy corner)\n" +
        "│ ▸ <name>  then  │   <status> OK|FAIL|SKIP · <ms>ms  ✔|✘|⊘   nested child request\n" +
        "│ ── REQ ── / │ ── RESP ──   HTTP exchange, every line under the │ spine\n" +
        "└ ✔|✘ <intent>  ⟶ produces   runbook step closes\n" +
        "the final block reads OUTCOME PASSED or FAILED; on FAILED it carries the\n" +
        "  failing step, a one-line error summary, and the full stacktrace + cause chain\n" +
        "=====================================================\n"
    )
  }

  // --- internals ---

  /** Append [s], flushing immediately so a `tail -f` reader sees every write live. Swallow-safe. */
  private fun write(s: String) {
    try {
      out.write(s)
      out.flush()
    } catch (e: Exception) {
      logger.warn { "FileRunLogSink write failed (ignored): $e" }
    }
  }

  /** Repoint `latest.log` -> this run file. Symlink first; fall back to a pointer file. */
  private fun repointLatest() {
    val latest = testDir.resolve(LATEST)
    val target = runFile.fileName
    try {
      Files.deleteIfExists(latest)
      Files.createSymbolicLink(latest, target)
    } catch (e: Exception) {
      try {
        Files.writeString(latest, target.toString(), StandardCharsets.UTF_8)
      } catch (ignored: IOException) {
        logger.warn { "FileRunLogSink latest pointer failed (ignored): $e" }
      }
    }
  }

  companion object {
    /** Filesystem-safe instant: ISO-8601 with `:` replaced by `-`, UTC. */
    private val STAMP: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC)
    private val HEADER_TIME: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)
    private const val LATEST = "latest.log"

    /**
     * Open a live writer for one run, creating `<logsDir>/<runLabel>/` and the timestamped file,
     * and writing the banner at line 1. Throws [IOException] on failure — callers that want the
     * never-fail guarantee use [openOrNoOp].
     */
    @JvmStatic
    @Throws(IOException::class)
    fun open(
      logsDir: Path,
      runLabel: String,
      mode: String,
      startedAt: Instant,
      config: FileRunLogConfig,
    ): FileRunLogSink {
      val testDir = logsDir.resolve(runLabel)
      Files.createDirectories(testDir)
      val runFile = testDir.resolve(STAMP.format(startedAt) + ".log")
      val out =
        Files.newBufferedWriter(
          runFile,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND,
        )
      val sink = FileRunLogSink(runFile, testDir, runLabel, mode, startedAt, config, out)
      sink.writeBanner()
      return sink
    }

    /**
     * Never-throw factory: a real sink, or `null` when opening failed (per-run file logging off).
     */
    @JvmStatic
    fun openOrNoOp(
      logsDir: Path,
      runLabel: String,
      mode: String,
      startedAt: Instant,
      config: FileRunLogConfig,
    ): FileRunLogSink? =
      try {
        open(logsDir, runLabel, mode, startedAt, config)
      } catch (e: Exception) {
        // Belt-and-suspenders: okio can surface a checked IOException undeclared through Kotlin, so
        // catch broadly. Any open failure degrades to null (logging disabled for this run).
        logger.warn { "FileRunLogSink open failed; per-run file logging disabled for this run: $e" }
        null
      }
  }
}
