/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

/**
 * Controls the level of detail when serializing [Rundown] to JSON.
 *
 * Designed for AI agents and MCP protocols that may need different levels of detail.
 */
enum class RundownVerbosity {
  /**
   * Minimal information: execution counts, success flags, environment keys only.
   *
   * Use when: Quick overview needed, bandwidth-constrained scenarios.
   */
  SUMMARY,

  /**
   * Standard information: SUMMARY + step summaries (name, index, success status, failure types).
   *
   * Use when: Understanding what happened at step level without full details.
   */
  STANDARD,

  /**
   * Detailed information: STANDARD + request/response metadata (URI, HTTP method, status codes,
   * headers) but excludes request/response bodies.
   *
   * Use when: Debugging or analysis that needs HTTP-level details without payload inspection.
   */
  DETAILED,

  /**
   * Full information: Everything including request/response bodies and complete environment.
   *
   * Use when: Complete forensic analysis or debugging is needed.
   */
  FULL,
}
