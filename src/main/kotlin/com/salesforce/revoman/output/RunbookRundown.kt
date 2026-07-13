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
 * adding step pairing and (Task 8) `toMermaid()`/`toMarkdown()` views.
 */
class RunbookRundown(
  val name: String?,
  val stepRundowns: List<Pair<RunbookStep, Rundown>>,
) : List<Rundown> by (stepRundowns.map { it.second }) {

  /**
   * Explicit accessor for the per-step [Rundown]s (this object also IS that list via delegation).
   */
  val rundowns: List<Rundown>
    get() = this

  fun stepFor(intent: String): Pair<RunbookStep, Rundown>? = stepRundowns.firstOrNull {
    it.first.intent == intent
  }

  fun toMarkdown(): String = renderRunbookMarkdown(this)

  fun toMermaid(): String = renderRunbookMermaid(this)
}
