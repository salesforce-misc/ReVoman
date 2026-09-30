/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

/** The reasoning layer's decision for one intent — proceed, ask, confirm, or no match. */
sealed interface ReasoningOutcome {
  data class NoMatch(val intent: String) : ReasoningOutcome

  /** The ask-don't-guess result: top-2 within margin, or a required slot missing/invalid. */
  data class Clarify(val question: String, val candidates: List<String>) : ReasoningOutcome

  /** A confident write: preview shown, nothing executed until the human confirms. */
  data class ConfirmRequired(val preview: ActionPreview) : ReasoningOutcome

  /** A confident read: safe to execute. */
  data class Proceed(val graph: String, val slots: Map<String, String>) : ReasoningOutcome
}
