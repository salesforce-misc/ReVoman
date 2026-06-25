/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

/** Why a Rundown's step execution loop terminated. */
enum class StopReason {
  /** All picked steps ran to natural completion (the default, no directive intervened). */
  COMPLETED,
  /** A `pm.execution.setNextRequest(null)` stopped the run. */
  STOPPED_BY_DIRECTIVE,
  /**
   * A step failed and halt config (`haltOnAnyFailure`/`haltOnFailureOfTypeExcept`) halted the run.
   */
  HALTED_ON_FAILURE,
  /** A jump loop exceeded the per-run execution budget (`maxStepExecutionFactor`). */
  LOOP_BUDGET_EXCEEDED,
}
