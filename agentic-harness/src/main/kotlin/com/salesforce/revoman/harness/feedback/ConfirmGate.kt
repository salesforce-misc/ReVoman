/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.feedback

/** The write the agent proposes at the human confirm gate. */
data class Proposal(val utterance: String, val graph: String, val slots: Map<String, String>)

/** What the human does at the gate. */
sealed interface Decision {
  /** Approve the proposal as-is. */
  object Confirm : Decision

  /** Approve after fixing the slots. */
  data class Edit(val correctedSlots: Map<String, String>) : Decision

  /** Reject; optionally state the graph that should have been chosen. */
  data class Reject(val correctGraph: String? = null) : Decision
}

/** The training signal the gate emits — the design's "free labeling machine". */
sealed interface FeedbackLabel {
  data class Positive(val proposal: Proposal) : FeedbackLabel

  data class CorrectionPair(val proposal: Proposal, val correctedSlots: Map<String, String>) :
    FeedbackLabel

  data class Negative(val proposal: Proposal, val correctGraph: String?) : FeedbackLabel
}

/**
 * The human-in-the-loop confirm gate on every write. Because a human confirms every mutation, the
 * gate is the highest-value labeling signal available at no extra cost: confirm → positive, edit →
 * a correction pair (proposed vs fixed), reject → a negative (mined for `when_not_to_use`).
 */
object ConfirmGate {
  fun apply(proposal: Proposal, decision: Decision): FeedbackLabel =
    when (decision) {
      is Decision.Confirm -> FeedbackLabel.Positive(proposal)
      is Decision.Edit -> FeedbackLabel.CorrectionPair(proposal, decision.correctedSlots)
      is Decision.Reject -> FeedbackLabel.Negative(proposal, decision.correctGraph)
    }
}
