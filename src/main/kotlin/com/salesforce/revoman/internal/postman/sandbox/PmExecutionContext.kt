/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

/** Which sandbox lifecycle script is running. Maps to Postman's event `listen` value. */
internal enum class ScriptTarget(val listen: String) {
  PRE_REQUEST("prerequest"),
  TEST("test"),
}

/** A single variable scope (environment / globals / collectionVariables) as key→value. */
internal data class PmScope(val id: String, val values: Map<String, Any?>)

/**
 * Everything the sandbox needs to execute one script. Variable scopes are snapshots taken from
 * [com.salesforce.revoman.output.postman.PostmanEnvironment] (and friends) before execution; the
 * sandbox returns mutated copies in [PmExecutionResult].
 */
internal data class PmExecutionContext(
  val environment: PmScope,
  val globals: PmScope = PmScope("globals", emptyMap()),
  val collectionVariables: PmScope = PmScope("collectionVariables", emptyMap()),
  val request: Map<String, Any?>? = null,
  val response: Map<String, Any?>? = null,
)

/** One `pm.test`/legacy `test` assertion result reported by the sandbox. */
internal data class PmAssertion(
  val name: String,
  val index: Int,
  val passed: Boolean,
  val skipped: Boolean,
  val error: String?,
)

/**
 * The outcome of a single sandbox execution.
 * - [environment]/[globals]/[collectionVariables]: the FULL post-execution scope values (caller
 *   diffs against the pre-snapshot to derive produced/unset).
 * - [assertions]: pm.test results (failures are data, NOT thrown).
 * - [error]: a thrown script error (pre-req/test JS failure) — null on success.
 * - [nextRequest]/[skipRequest]: control-flow directives (Phase 2 wires them to the sequencer;
 *   Phase 1 records them but the stubs never set them).
 */
internal data class PmExecutionResult(
  val environment: Map<String, Any?>,
  val globals: Map<String, Any?>,
  val collectionVariables: Map<String, Any?>,
  val assertions: List<PmAssertion>,
  val error: Throwable?,
  val nextRequest: String? = null,
  val skipRequest: Boolean = false,
)
