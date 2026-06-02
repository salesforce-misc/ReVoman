/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

/**
 * Env keys a step touched during execution. Vocabulary aligns with the ledger producer/consumer
 * thesis: producers WRITE ids (skippable on reuse); consumers/asserters only READ.
 */
data class StepEnvVars(
  /** Keys WRITTEN via `pm.environment.set(...)` / beforeRequest. The producer signal. */
  @JvmField val produced: Set<String> = emptySet(),
  /** Keys READ via `{{key}}` substitution or pm getters. Idea-3 mutation-set fuel. */
  @JvmField val consumed: Set<String> = emptySet(),
)
