/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.contract

/**
 * The provenance of one environment variable within a graph run: which step WROTE it
 * (`pm.environment.set`), and which steps READ it (`{{key}}`). This is the data-lineage ReVoman
 * captures in [com.salesforce.revoman.output.report.StepEnvVars] but never surfaces — the piece
 * that lets the contract explain "where did this value come from".
 */
data class DataFlowEdge(
  val key: String,
  val producedByStep: String?,
  val consumedBySteps: List<String>,
)
