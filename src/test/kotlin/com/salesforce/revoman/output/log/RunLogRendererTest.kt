/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import com.salesforce.revoman.input.config.Phase
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class RunLogRendererTest {

  @Test
  fun `gutter prefixes every line with the spine and keeps blank lines as a bare spine`() {
    val out = RunLogRenderer.gutter("POST /s\n\n{}")
    out shouldContain "│ POST /s\n"
    out shouldContain "\n│\n" // the blank separator line becomes a bare "│"
    out shouldContain "│ {}\n"
  }

  @Test
  fun `gutter trims a trailing newline so the spine stops at the last content line`() {
    // A body that ends with a newline must NOT emit a spurious bare "│" past its last content line.
    val out = RunLogRenderer.gutter("HTTP/1.1 200 OK\n{}\n")
    out shouldEndWith "│ {}\n"
    out shouldNotContain "│ {}\n│\n" // no trailing bare-spine line after the body
  }

  @Test
  fun `finished header carries mid-dot separator and a success glyph`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished(
          path = "schedule-single",
          httpStatus = 200,
          produced = setOf("saId"),
          consumed = emptySet(),
          tookMs = 4836,
          outcome = Outcome.SUCCESS,
          producedValues = mapOf("saId" to "08pxx0000004CiWAAU"),
        )
      )
    out shouldContain "│   200 OK · 4836ms  ✔\n"
  }

  @Test
  fun `finished header shows the failed glyph`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished("s", 400, emptySet(), emptySet(), 5, Outcome.FAILED)
      )
    out shouldContain "│   400 FAIL · 5ms  ✘\n"
  }

  @Test
  fun `finished header shows the skipped glyph`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished("s", null, emptySet(), emptySet(), 0, Outcome.SKIPPED)
      )
    out shouldContain "│   null SKIP · 0ms  ⊘\n"
  }

  @Test
  fun `values line prefers producedValues and consumedValues over key sets`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished(
          path = "auth",
          httpStatus = 200,
          produced = setOf("accessToken"),
          consumed = setOf("baseUrl"),
          tookMs = 12,
          outcome = Outcome.SUCCESS,
          producedValues = mapOf("accessToken" to "tok123"),
          consumedValues = mapOf("baseUrl" to "https://localhost:6101"),
        )
      )
    out shouldContain "│   ⟵ baseUrl=https://localhost:6101   ⟶ accessToken=tok123\n"
  }

  @Test
  fun `values line falls back to key sets when no values captured`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished("s", 200, setOf("saId"), setOf("token"), 1, Outcome.SUCCESS)
      )
    out shouldContain "│   ⟵ token   ⟶ saId\n"
  }

  @Test
  fun `values line shows empty-set glyph for an empty side and is omitted when both empty`() {
    val bothEmpty =
      RunLogRenderer.render(
        StepEvent.StepFinished("s", 200, emptySet(), emptySet(), 1, Outcome.SUCCESS)
      )
    bothEmpty shouldNotContain "⟵"
    val oneSide =
      RunLogRenderer.render(
        StepEvent.StepFinished(
          "s",
          200,
          setOf("saId"),
          emptySet(),
          1,
          Outcome.SUCCESS,
          producedValues = mapOf("saId" to "08p"),
        )
      )
    oneSide shouldContain "│   ⟵ ∅   ⟶ saId=08p\n"
  }

  @Test
  fun `REQ and RESP render as sub-rules with fully-guttered bodies`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished(
          path = "schedule",
          httpStatus = 200,
          produced = emptySet(),
          consumed = emptySet(),
          tookMs = 5,
          outcome = Outcome.SUCCESS,
          requestMsg = "POST /schedule\n\n{\n  \"a\": 1\n}",
          responseMsg = "HTTP/1.1 200 OK\n\n{\n  \"ok\": true\n}",
        )
      )
    out shouldContain "│ ── REQ ─"
    out shouldContain "│ POST /schedule\n"
    out shouldContain "│   \"a\": 1\n" // body line under the spine, original indent preserved
    out shouldContain "│ ── RESP ─"
    out shouldContain "│ HTTP/1.1 200 OK\n"
    out shouldContain "│   \"ok\": true\n"
  }

  @Test
  fun `REQ present but RESP omitted when responseMsg is null`() {
    val out =
      RunLogRenderer.render(
        StepEvent.StepFinished(
          "s",
          null,
          emptySet(),
          emptySet(),
          5,
          Outcome.FAILED,
          requestMsg = "POST /s",
          responseMsg = null,
        )
      )
    out shouldContain "│ ── REQ ─"
    out shouldNotContain "── RESP ─"
  }

  @Test
  fun `child StepStarted renders with the heavier caret`() {
    val out = RunLogRenderer.render(StepEvent.StepStarted("10-book", "Book Appointment"))
    out shouldStartWith "│ ▸ "
    out shouldContain "Book Appointment"
  }

  @Test
  fun `coarse step open close and phase rule are unchanged`() {
    RunLogRenderer.render(StepEvent.PhaseEntered(Phase.SEED)) shouldStartWith "━━ SEED "
    val open =
      RunLogRenderer.render(
        StepEvent.RunbookStepStarted("s", "seed SAs", Phase.SEED, setOf("policyId"), false)
      )
    open shouldContain "┌ ▶ seed SAs"
    open shouldContain "⟵ policyId"
    val close =
      RunLogRenderer.render(
        StepEvent.RunbookStepFinished("s", "seed SAs", Outcome.SUCCESS, mapOf("saId" to "a07"), 8)
      )
    close shouldContain "└ ✔ seed SAs"
    close shouldContain "⟶ saId=a07"
  }
}
