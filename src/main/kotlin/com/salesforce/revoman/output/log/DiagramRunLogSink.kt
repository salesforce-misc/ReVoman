/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * A [RunLogSink] that renders ONE run as a Mermaid `sequenceDiagram`. Unlike the per-event live
 * text sinks ([ConsoleRunLogSink]/[FileRunLogSink]), it ACCUMULATES the run's HTTP interactions
 * during the run and renders the whole diagram on [close] — a sequence diagram is a whole-run
 * artifact. All grammar delegates to [DiagramRenderer] (the single source). Writes
 * `<logsDir>/<runLabel>/<timestamp>.mmd` and repoints `latest.mmd`. Best-effort and never-throw:
 * any render/IO error is swallowed (logged once), honoring the [RunLogSink] contract.
 * Single-threaded for its lifetime per that contract.
 */
@Suppress("TooGenericExceptionCaught")
class DiagramRunLogSink
private constructor(
  private val mmdFile: Path,
  private val testDir: Path,
) : RunLogSink {

  private val interactions = mutableListOf<RunInteraction>()
  private var seq = 0
  private var currentPhase: String? = null
  private var currentIntent: String? = null
  private var currentUnderTest = false

  /** No-op: the diagram is built from structured events, not narration lines. */
  override fun line(level: LogLevel, message: String) {}

  override fun event(event: StepEvent) {
    runCatching { accumulate(event) }
      .onFailure { logger.debug { "DiagramRunLogSink event failed (ignored): $it" } }
  }

  private fun accumulate(event: StepEvent) {
    when (event) {
      is StepEvent.PhaseEntered -> currentPhase = event.phase.name
      is StepEvent.RunbookStepStarted -> {
        currentIntent = event.intent
        currentUnderTest = event.underTest
      }
      is StepEvent.RunbookStepFinished -> {
        currentIntent = null
        currentUnderTest = false
      }
      is StepEvent.StepFinished ->
        event.host?.let { host ->
          interactions.add(
            RunInteraction(
              seq = seq++,
              from = "User",
              to = host,
              method = event.method ?: "?",
              requestPath = event.requestPath ?: "",
              status = event.httpStatus,
              tookMs = event.tookMs,
              outcome = event.outcome,
              produced = event.produced,
              consumed = event.consumed,
              phase = currentPhase,
              intent = currentIntent,
              underTest = currentUnderTest,
            )
          )
        }
      else -> {} // other events do not contribute an interaction
    }
  }

  override fun close() {
    runCatching {
        val diagram = DiagramRenderer.render(interactions.toList())
        Files.writeString(mmdFile, diagram, StandardCharsets.UTF_8)
        repointLatest()
      }
      .onFailure { logger.debug { "DiagramRunLogSink close failed (ignored): $it" } }
  }

  /** Repoint `latest.mmd` -> this run file. Symlink first; fall back to a pointer file. */
  private fun repointLatest() {
    val latest = testDir.resolve(LATEST)
    val target = mmdFile.fileName
    try {
      Files.deleteIfExists(latest)
      Files.createSymbolicLink(latest, target)
    } catch (e: Exception) {
      try {
        Files.writeString(latest, target.toString(), StandardCharsets.UTF_8)
      } catch (ignored: IOException) {
        logger.warn { "DiagramRunLogSink latest pointer failed (ignored): $e" }
      }
    }
  }

  companion object {
    /** Filesystem-safe instant: ISO-8601 with `:` replaced by `-`, UTC. */
    private val STAMP: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC)
    private const val LATEST = "latest.mmd"

    /**
     * Open a diagram sink for one run, creating `<logsDir>/<runLabel>/`. Throws [IOException] on a
     * directory-creation failure — callers wanting the never-fail guarantee use [openOrNoOp].
     */
    @JvmStatic
    @Throws(IOException::class)
    fun open(logsDir: Path, runLabel: String, startedAt: Instant): DiagramRunLogSink {
      val testDir = logsDir.resolve(runLabel)
      Files.createDirectories(testDir)
      val mmdFile = testDir.resolve(STAMP.format(startedAt) + ".mmd")
      return DiagramRunLogSink(mmdFile, testDir)
    }

    /** Never-throw factory: a real sink, or `null` when opening failed. */
    @JvmStatic
    fun openOrNoOp(logsDir: Path, runLabel: String, startedAt: Instant): DiagramRunLogSink? =
      try {
        open(logsDir, runLabel, startedAt)
      } catch (e: Exception) {
        logger.warn { "DiagramRunLogSink open failed; diagram disabled for this run: $e" }
        null
      }
  }
}
