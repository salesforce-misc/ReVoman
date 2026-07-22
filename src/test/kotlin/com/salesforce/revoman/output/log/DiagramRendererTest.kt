/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class DiagramRendererTest {

  private fun interaction(
    seq: Int,
    host: String,
    method: String = "GET",
    path: String = "/x",
    status: Int? = 200,
    tookMs: Long = 10L,
    outcome: Outcome = Outcome.SUCCESS,
    produced: Set<String> = emptySet(),
    consumed: Set<String> = emptySet(),
    phase: String? = null,
  ): RunInteraction =
    RunInteraction(
      seq,
      "User",
      host,
      method,
      path,
      status,
      tookMs,
      outcome,
      produced,
      consumed,
      phase,
    )

  @Test
  fun `empty run renders a minimal valid diagram`() {
    DiagramRenderer.render(emptyList()) shouldBe "sequenceDiagram\n    actor User\n"
  }

  @Test
  fun `single host renders participant request and response`() {
    val out =
      DiagramRenderer.render(listOf(interaction(0, "pokeapi.co", "GET", "/api/v2/pokemon/ditto")))
    out shouldStartWith "sequenceDiagram\n    actor User\n"
    out shouldContain "participant h0 as pokeapi.co\n"
    out shouldContain "User->>h0: GET /api/v2/pokemon/ditto\n"
    out shouldContain "h0-->>User: 200 (10ms)\n"
  }

  @Test
  fun `distinct hosts get distinct participants in first-seen order`() {
    val out =
      DiagramRenderer.render(
        listOf(
          interaction(0, "pokeapi.co"),
          interaction(1, "restful-api.dev"),
          interaction(2, "pokeapi.co"),
        )
      )
    out shouldContain "participant h0 as pokeapi.co\n"
    out shouldContain "participant h1 as restful-api.dev\n"
    // first-seen order: pokeapi.co (h0) declared before restful-api.dev (h1)
    (out.indexOf("participant h0") < out.indexOf("participant h1")) shouldBe true
    // pokeapi.co reused on the third call -> still h0 (stable id)
    out shouldContain "User->>h0: GET /x\n"
    out shouldContain "User->>h1: GET /x\n"
  }

  @Test
  fun `failed step renders ERR status`() {
    val out =
      DiagramRenderer.render(listOf(interaction(0, "h", status = null, outcome = Outcome.FAILED)))
    out shouldContain "h0-->>User: ERR (10ms) ✘\n"
  }

  @Test
  fun `failed step with HTTP 200 renders the honest failure marker`() {
    val out =
      DiagramRenderer.render(listOf(interaction(0, "h", status = 200, outcome = Outcome.FAILED)))
    out shouldContain "h0-->>User: 200 (10ms) ✘\n"
  }

  @Test
  fun `phase boundary emits a note when the phase changes`() {
    val out =
      DiagramRenderer.render(
        listOf(
          interaction(0, "a", phase = "SEED"),
          interaction(1, "a", phase = "SEED"),
          interaction(2, "a", phase = "TEST"),
        )
      )
    out shouldContain "Note over User: ━━ SEED"
    out shouldContain "Note over User: ━━ TEST"
    // only two phase notes (SEED once, TEST once), not per-interaction
    out.split("Note over User: ━━ SEED").size shouldBe 2
  }

  @Test
  fun `env data-flow renders a note linking consumer to producer host`() {
    val out =
      DiagramRenderer.render(
        listOf(
          interaction(0, "auth.host", produced = setOf("accessToken")),
          interaction(1, "api.host", consumed = setOf("accessToken")),
        )
      )
    out shouldContain "Note right of h1: ⟵ accessToken from h0\n"
  }

  @Test
  fun `duplicate calls are flagged in the inefficiency summary`() {
    val out =
      DiagramRenderer.render(
        listOf(
          interaction(0, "pokeapi.co", "GET", "/api/v2/pokemon/ditto"),
          interaction(1, "pokeapi.co", "GET", "/api/v2/pokemon/ditto"),
          interaction(2, "pokeapi.co", "GET", "/api/v2/pokemon/pikachu"),
        )
      )
    out shouldContain "Note over User: ⚠ 2× GET pokeapi.co/api/v2/pokemon/ditto"
    // the non-duplicate is NOT flagged
    (out.contains("⚠ 1×")) shouldBe false
  }

  @Test
  fun `data-flow notes for multiple consumed keys are emitted in sorted order`() {
    val out =
      DiagramRenderer.render(
        listOf(
          interaction(0, "auth.host", produced = setOf("zebra")),
          interaction(1, "cfg.host", produced = setOf("alpha")),
          interaction(2, "api.host", consumed = setOf("zebra", "alpha")),
        )
      )
    // Both notes point at the consumer (h2); sorted -> "alpha" note precedes "zebra" note.
    val alphaIdx = out.indexOf("Note right of h2: ⟵ alpha from h1")
    val zebraIdx = out.indexOf("Note right of h2: ⟵ zebra from h0")
    (alphaIdx > -1) shouldBe true
    (zebraIdx > -1) shouldBe true
    (alphaIdx < zebraIdx) shouldBe true
  }

  @Test
  fun `same key produced by two hosts points at the most recent producer`() {
    val out =
      DiagramRenderer.render(
        listOf(
          interaction(0, "auth1.host", produced = setOf("token")),
          interaction(1, "auth2.host", produced = setOf("token")),
          interaction(2, "api.host", consumed = setOf("token")),
        )
      )
    // The data-flow note should point at h1 (auth2, the later producer), not h0.
    out shouldContain "Note right of h2: ⟵ token from h1\n"
    // Ensure it does NOT point at the first producer (h0).
    (out.contains("⟵ token from h0")) shouldBe false
  }
}
