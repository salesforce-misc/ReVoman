/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.reasoning

/**
 * The confirm gate's proposed-action preview for a write: exactly what would run, shown to the
 * human, executed only on confirm. Carries what `GraphRunner.runChain` needs so confirmation
 * executes without re-deriving anything.
 */
data class ActionPreview(
  val graph: String,
  val slots: Map<String, String>,
  val chain: List<String>,
  val isWrite: Boolean,
)
