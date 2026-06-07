/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.ExeStepPick
import com.salesforce.revoman.internal.postman.template.Header
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import com.salesforce.revoman.internal.postman.template.Url
import com.salesforce.revoman.output.ledger.LedgerEntry
import com.salesforce.revoman.output.ledger.LedgerSnapshot
import com.salesforce.revoman.output.report.Step
import org.junit.jupiter.api.Test

class LedgerDecisionTest {
  private fun step(hash: String): Step =
    Step(index = "1", rawPMStep = Item(name = "create-sa"), sourceHash = hash)

  @Test
  fun `skips when produced keys present and hash matches`() {
    val s = step("h1")
    val snap =
      LedgerSnapshot(
        "00D",
        mapOf(s.path to LedgerEntry(setOf("saId1"), s.sourceHash)),
        mapOf("saId1" to "08p1"),
      )
    assertThat(ledgerSkipDecision(s, snap, setOf("saId1"))).isTrue()
  }

  @Test
  fun `does NOT skip when a produced key is missing from env`() {
    val s = step("h1")
    val snap =
      LedgerSnapshot(
        "00D",
        mapOf(s.path to LedgerEntry(setOf("saId1"), s.sourceHash)),
        mapOf("saId1" to "08p1"),
      )
    assertThat(ledgerSkipDecision(s, snap, emptySet())).isFalse()
  }

  @Test
  fun `does NOT skip when produces is empty (read-only step)`() {
    val s = step("h1")
    val snap =
      LedgerSnapshot("00D", mapOf(s.path to LedgerEntry(emptySet(), s.sourceHash)), emptyMap())
    assertThat(ledgerSkipDecision(s, snap, setOf("anything"))).isFalse()
  }

  @Test
  fun `does NOT skip on hash mismatch (warn-and-run)`() {
    val s = step("NEW")
    val snap =
      LedgerSnapshot(
        "00D",
        mapOf(s.path to LedgerEntry(setOf("saId1"), "OLD")),
        mapOf("saId1" to "08p1"),
      )
    assertThat(ledgerSkipDecision(s, snap, setOf("saId1"))).isFalse()
  }

  @Test
  fun `does NOT skip when no entry for the step`() {
    val s = step("h1")
    assertThat(ledgerSkipDecision(s, LedgerSnapshot.EMPTY, setOf("saId1"))).isFalse()
  }

  @Test
  fun `skips only when env is a SUPERSET of all produced keys`() {
    val s = step("h1")
    val snap =
      LedgerSnapshot(
        "00D",
        mapOf(s.path to LedgerEntry(setOf("saId1", "saId2"), s.sourceHash)),
        mapOf("saId1" to "08p1", "saId2" to "08p2"),
      )
    assertThat(ledgerSkipDecision(s, snap, setOf("saId1"))).isFalse() // missing saId2
    assertThat(ledgerSkipDecision(s, snap, setOf("saId1", "saId2"))).isTrue() // both present
  }

  @Test
  fun `does NOT skip a step that opts out via x-revoman-ledger off header (even when otherwise skippable)`() {
    // An act-step under assertion that ALSO produces a key (e.g. a booking call that
    // pm.environment.set()s its own scheduled-time window) is, by the base predicate, a perfectly
    // cacheable producer — but its RESPONSE is the assertion target, so it must always dispatch.
    val optedOut =
      Step(
        index = "1",
        rawPMStep =
          Item(
            name = "schedule-booking",
            request = Request(header = listOf(Header(Step.LEDGER_HEADER, Step.LEDGER_OFF))),
          ),
        sourceHash = "h1",
      )
    val snap =
      LedgerSnapshot(
        "00D",
        mapOf(optedOut.path to LedgerEntry(setOf("bookingStart"), optedOut.sourceHash)),
        mapOf("bookingStart" to "2026-07-01T15:00:00Z"),
      )
    // Base predicate would say skip (produces present, hash matches, env superset) — opt-out wins.
    assertThat(ledgerSkipDecision(optedOut, snap, setOf("bookingStart"))).isFalse()
  }

  @Test
  fun `does NOT skip a step matched by a Kick-level ledgerOptOut pick (URL pattern)`() {
    val actStep =
      Step(
        index = "1",
        rawPMStep =
          Item(
            name = "schedule",
            request = Request(url = Url(raw = "{{baseUrl}}/connect/unified-scheduling/actions/schedule")),
          ),
        sourceHash = "h1",
      )
    val snap =
      LedgerSnapshot(
        "00D",
        mapOf(actStep.path to LedgerEntry(setOf("bookingStart"), actStep.sourceHash)),
        mapOf("bookingStart" to "v"),
      )
    val optOut = listOf(ExeStepPick.stepEndingWithURIPathOfAny("/actions/schedule"))
    // Base predicate would skip; the URL-pattern pick opts it out.
    assertThat(ledgerSkipDecision(actStep, snap, setOf("bookingStart"), optOut)).isFalse()
    // A different step NOT matched by the pick (and no header) is still skippable.
    val setupStep =
      Step(
        index = "2",
        rawPMStep =
          Item(name = "create-policy", request = Request(url = Url(raw = "{{baseUrl}}/sobjects/SchedulingPolicy"))),
        sourceHash = "h2",
      )
    val snap2 =
      LedgerSnapshot(
        "00D",
        mapOf(setupStep.path to LedgerEntry(setOf("policyId"), setupStep.sourceHash)),
        mapOf("policyId" to "p1"),
      )
    assertThat(ledgerSkipDecision(setupStep, snap2, setOf("policyId"), optOut)).isTrue()
  }

  @Test
  fun `opt-out header is case-insensitive on key and value`() {
    val optedOut =
      Step(
        index = "1",
        rawPMStep =
          Item(
            name = "schedule-booking",
            request = Request(header = listOf(Header("X-ReVoman-Ledger", "OFF"))),
          ),
        sourceHash = "h1",
      )
    val snap =
      LedgerSnapshot(
        "00D",
        mapOf(optedOut.path to LedgerEntry(setOf("k"), optedOut.sourceHash)),
        mapOf("k" to "v"),
      )
    assertThat(ledgerSkipDecision(optedOut, snap, setOf("k"))).isFalse()
  }

  @Test
  fun `a header other than off does NOT opt out (still skippable)`() {
    val notOptedOut =
      Step(
        index = "1",
        rawPMStep =
          Item(
            name = "create-sa",
            request = Request(header = listOf(Header(Step.LEDGER_HEADER, "on"))),
          ),
        sourceHash = "h1",
      )
    val snap =
      LedgerSnapshot(
        "00D",
        mapOf(notOptedOut.path to LedgerEntry(setOf("saId1"), notOptedOut.sourceHash)),
        mapOf("saId1" to "08p1"),
      )
    assertThat(ledgerSkipDecision(notOptedOut, snap, setOf("saId1"))).isTrue()
  }

  @Test
  fun `does NOT skip when either hash is empty (unknown never matches unknown)`() {
    // A non-v3 step (sourceHash "") against an entry with an empty hash must NOT skip on "" == "".
    val s = step("")
    val snap =
      LedgerSnapshot(
        "00D",
        mapOf(s.path to LedgerEntry(setOf("saId1"), "")),
        mapOf("saId1" to "08p1"),
      )
    assertThat(ledgerSkipDecision(s, snap, setOf("saId1"))).isFalse()
  }

  // --- Collision guard: a key produced by >1 step is only safely skippable at its LAST producer
  // ---

  private fun namedStep(name: String, hash: String = "h"): Step =
    Step(index = "1", rawPMStep = Item(name = name), sourceHash = hash)

  @Test
  fun `no collision - every key produced by one step - nothing shadowed`() {
    val s1 = namedStep("create-sa-1")
    val s2 = namedStep("create-sa-2")
    val snap =
      LedgerSnapshot(
        "00D",
        mapOf(
          s1.path to LedgerEntry(setOf("saId1"), "h"),
          s2.path to LedgerEntry(setOf("saId2"), "h"),
        ),
        emptyMap(),
      )
    assertThat(shadowedProducerPaths(listOf(s1, s2), snap)).isEmpty()
  }

  @Test
  fun `two steps set the same key - only the EARLIER producer is shadowed`() {
    val early = namedStep("create-sa-early")
    val late = namedStep("recreate-sa-late")
    val snap =
      LedgerSnapshot(
        "00D",
        mapOf(
          early.path to LedgerEntry(setOf("saId1"), "h"),
          late.path to LedgerEntry(setOf("saId1"), "h"),
        ),
        emptyMap(),
      )
    // Execution order is the list order: early before late.
    val shadowed = shadowedProducerPaths(listOf(early, late), snap)
    assertThat(shadowed).containsExactly(early.path)
    assertThat(shadowed).doesNotContain(late.path)
  }

  @Test
  fun `three producers of one key - all but the last are shadowed`() {
    val a = namedStep("set-x-a")
    val b = namedStep("set-x-b")
    val c = namedStep("set-x-c")
    val snap =
      LedgerSnapshot(
        "00D",
        mapOf(
          a.path to LedgerEntry(setOf("x"), "h"),
          b.path to LedgerEntry(setOf("x"), "h"),
          c.path to LedgerEntry(setOf("x"), "h"),
        ),
        emptyMap(),
      )
    assertThat(shadowedProducerPaths(listOf(a, b, c), snap)).containsExactly(a.path, b.path)
  }

  @Test
  fun `a step producing a collision key AND a unique key is still shadowed (must run)`() {
    // If skipped, the collision key would be injected with the LATER value -> intermediate consumer
    // wrong. So a step is shadowed if ANY produced key is re-set by a later step.
    val early = namedStep("create-both")
    val late = namedStep("recreate-shared")
    val snap =
      LedgerSnapshot(
        "00D",
        mapOf(
          early.path to LedgerEntry(setOf("shared", "uniqueToEarly"), "h"),
          late.path to LedgerEntry(setOf("shared"), "h"),
        ),
        emptyMap(),
      )
    assertThat(shadowedProducerPaths(listOf(early, late), snap)).containsExactly(early.path)
  }

  @Test
  fun `steps without a ledger entry are ignored by the collision scan`() {
    val ledgered = namedStep("ledgered")
    val unledgered = namedStep("not-in-ledger")
    val snap =
      LedgerSnapshot("00D", mapOf(ledgered.path to LedgerEntry(setOf("k"), "h")), emptyMap())
    assertThat(shadowedProducerPaths(listOf(unledgered, ledgered), snap)).isEmpty()
  }

  @Test
  fun `Kick defaults to EMPTY ledger - builder ledger() and immutable overrideLedger() set it`() {
    val kick = Kick.configure().off()
    assertThat(kick.ledger()).isEqualTo(LedgerSnapshot.EMPTY)
    val snap = LedgerSnapshot("00D", emptyMap(), emptyMap())
    // builder setter (mirrors haltOnAnyFailure/insecureHttp `@Value.Default` setters)
    val kick2 = Kick.configure().ledger(snap).off()
    assertThat(kick2.ledger()).isEqualTo(snap)
    // copy-with on the built immutable (the `with="override*"` style)
    assertThat(kick.overrideLedger(snap).ledger()).isEqualTo(snap)
  }
}
