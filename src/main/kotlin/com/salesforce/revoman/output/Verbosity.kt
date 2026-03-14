/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

/**
 * Controls the detail level of [Rundown.toJson] output.
 * - [SUMMARY]: Counts, success flags, and first failure info only — for quick health checks.
 * - [STANDARD]: Summary + all step reports with identity, status, request method/URI, response
 *   status, and failure details (no HTTP bodies).
 * - [VERBOSE]: Everything — HTTP headers, bodies, env snapshots, polling details, and stack traces.
 */
enum class Verbosity {
  SUMMARY,
  STANDARD,
  VERBOSE,
}
