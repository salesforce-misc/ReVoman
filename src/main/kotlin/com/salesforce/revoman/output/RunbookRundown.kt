/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import com.salesforce.revoman.input.config.RunbookStep

/**
 * The executed runbook: each [RunbookStep] paired with its [Rundown], in order. Implements
 * `List<Rundown>` so it drops in wherever today's `revUp(List<Kick>)` return is consumed, while
 * adding step pairing and the [toMermaid]/[toMarkdown] views.
 */
class RunbookRundown
private constructor(
  val name: String?,
  val stepRundowns: List<Pair<RunbookStep, Rundown>>,
  // The backing `List<Rundown>` view delegation forwards to. Held as a NAMED field (not
  // `stepRundowns.map { }` inlined into the `by` clause) so `equals`/`hashCode`/`toString` below
  // can
  // delegate to it directly — delegating them to `this` would recurse infinitely.
  private val rundownsView: List<Rundown>,
) : List<Rundown> by rundownsView {

  constructor(
    name: String?,
    stepRundowns: List<Pair<RunbookStep, Rundown>>,
  ) : this(name, stepRundowns, stepRundowns.map { it.second })

  /**
   * Explicit accessor for the per-step [Rundown]s (this object also IS that list via delegation).
   */
  val rundowns: List<Rundown>
    get() = rundownsView

  /** The (step, rundown) pair for [intent], or null if no step declared it. */
  fun stepFor(intent: String): Pair<RunbookStep, Rundown>? = stepRundowns.firstOrNull {
    it.first.intent == intent
  }

  /** A markdown table view of this runbook — one row per step. */
  fun toMarkdown(): String = renderRunbookMarkdown(this)

  /** A mermaid sequence-diagram view of this runbook — one message per step. */
  fun toMermaid(): String = renderRunbookMermaid(this)

  // Kotlin `by`-delegation forwards only the `List` interface members, NOT `equals`/`hashCode`/
  // `toString` (they are `Any` members). Without these overrides the type would keep identity
  // equals while `List` mandates structural equals, breaking the `List` contract (asymmetric with
  // a plain `List<Rundown>`). Equality is by the per-step [Rundown] list ONLY — [name] and the
  // step pairing in [stepRundowns] are deliberately excluded, matching `List` semantics.
  override fun equals(other: Any?): Boolean = rundownsView == other

  override fun hashCode(): Int = rundownsView.hashCode()

  override fun toString(): String = rundownsView.toString()
}
