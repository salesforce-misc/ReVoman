/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import com.salesforce.revoman.input.config.Phase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiagramRunLogSinkTest {

  private val ts: Instant = Instant.parse("2026-06-05T13:57:02Z")
  private val mmd = "2026-06-05T13-57-02.mmd"

  private fun mmdFile(logsDir: Path, label: String): Path = logsDir.resolve(label).resolve(mmd)

  private fun finished(
    host: String,
    path: String,
    produced: Set<String> = emptySet(),
    consumed: Set<String> = emptySet(),
  ) =
    StepEvent.StepFinished(
      path = path,
      httpStatus = 200,
      produced = produced,
      consumed = consumed,
      tookMs = 7L,
      outcome = Outcome.SUCCESS,
      method = "GET",
      host = host,
      requestPath = path,
    )

  @Test
  fun `writes a mmd sequence diagram on close`(@TempDir logsDir: Path) {
    val sink = DiagramRunLogSink.open(logsDir, "T.m", ts)
    sink.event(finished("pokeapi.co", "/api/v2/pokemon/ditto"))
    sink.close()
    val body = Files.readString(mmdFile(logsDir, "T.m"))
    body shouldStartWith "sequenceDiagram"
    body shouldContain "participant h0 as pokeapi.co"
    body shouldContain "User->>h0: GET /api/v2/pokemon/ditto"
  }

  @Test
  fun `tracks phase from PhaseEntered and plots it`(@TempDir logsDir: Path) {
    val sink = DiagramRunLogSink.open(logsDir, "T.m", ts)
    sink.event(StepEvent.PhaseEntered(Phase.SEED))
    sink.event(finished("a.host", "/x"))
    sink.close()
    Files.readString(mmdFile(logsDir, "T.m")) shouldContain "Note over User: ━━ SEED"
  }

  @Test
  fun `ignores StepFinished with no host`(@TempDir logsDir: Path) {
    val sink = DiagramRunLogSink.open(logsDir, "T.m", ts)
    sink.event(
      StepEvent.StepFinished(
        path = "hook-only",
        httpStatus = null,
        produced = emptySet(),
        consumed = emptySet(),
        tookMs = 1L,
        outcome = Outcome.SUCCESS,
      )
    )
    sink.close()
    // no participants, just the minimal header
    Files.readString(mmdFile(logsDir, "T.m")) shouldBe "sequenceDiagram\n    actor User\n"
  }

  @Test
  fun `latest_mmd points to the newest run`(@TempDir logsDir: Path) {
    DiagramRunLogSink.open(logsDir, "T.m", ts).close()
    val latest = logsDir.resolve("T.m").resolve("latest.mmd")
    Files.exists(latest) shouldBe true
    val pointed =
      if (Files.isSymbolicLink(latest)) Files.readSymbolicLink(latest).fileName.toString()
      else Files.readString(latest).trim()
    pointed shouldBe mmd
  }

  @Test
  fun `openOrNoOp on an unwritable dir returns null and does not throw`() {
    DiagramRunLogSink.openOrNoOp(Path.of("/dev/null/cannot/create"), "T.m", ts).shouldBeNull()
  }
}
