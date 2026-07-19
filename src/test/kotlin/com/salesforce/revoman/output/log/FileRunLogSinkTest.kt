/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileRunLogSinkTest {

  private val ts: Instant = Instant.parse("2026-06-05T13:57:02Z")
  private val stamp = "2026-06-05T13-57-02.log"

  private fun runFile(logsDir: Path, label: String): Path = logsDir.resolve(label).resolve(stamp)

  @Test
  fun `banner sits at line 1 with identity and legend`(@TempDir logsDir: Path) {
    val sink =
      FileRunLogSink.open(
        logsDir,
        "UnifiedValidationE2ETest.getSlots",
        "External",
        ts,
        FileRunLogConfig.DEFAULT_ALL,
      )
    sink.line(LogLevel.INFO, "***** Executing Step: get-slots *****")
    sink.close()

    val body = Files.readString(runFile(logsDir, "UnifiedValidationE2ETest.getSlots"))
    body shouldStartWith "=== ReVoman run"
    body shouldContain "[run] test=UnifiedValidationE2ETest.getSlots"
    body shouldContain "[run] mode=External"
    body shouldContain "--- legend ---"
    body shouldContain "Executing Step: get-slots"
  }

  @Test
  fun `latest points to the newest run`(@TempDir logsDir: Path) {
    FileRunLogSink.open(logsDir, "T.m", "FTest", ts, FileRunLogConfig.DEFAULT_ALL).close()
    val latest = logsDir.resolve("T.m").resolve("latest.log")
    Files.exists(latest) shouldBe true
    val pointed =
      if (Files.isSymbolicLink(latest)) Files.readSymbolicLink(latest).fileName.toString()
      else Files.readString(latest).trim()
    pointed shouldBe stamp
  }

  @Test
  fun `libLogs off drops INFO and DEBUG but keeps WARN`(@TempDir logsDir: Path) {
    val noLib = FileRunLogConfig(false, true, true, true, true, 10)
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, noLib)
    sink.line(LogLevel.DEBUG, "{{token}} resolved from scope 'environment'")
    sink.line(LogLevel.INFO, "***** Executing Step *****")
    sink.line(LogLevel.WARN, "heads up")
    sink.close()
    val body = Files.readString(runFile(logsDir, "T.m"))
    body shouldNotContain "resolved from scope"
    body shouldNotContain "Executing Step"
    body shouldContain "heads up"
  }

  @Test
  fun `libLogs on passes DEBUG narration`(@TempDir logsDir: Path) {
    val sink = FileRunLogSink.open(logsDir, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.line(LogLevel.DEBUG, "{{token}} resolved from scope 'environment'")
    sink.close()
    Files.readString(runFile(logsDir, "T.m")) shouldContain "resolved from scope"
  }

  @Test
  fun `openOrNoOp on an unwritable dir returns null and does not throw`() {
    val bogus = Path.of("/dev/null/cannot/create")
    val sink = FileRunLogSink.openOrNoOp(bogus, "T.m", "External", ts, FileRunLogConfig.DEFAULT_ALL)
    sink.shouldBeNull()
  }
}
