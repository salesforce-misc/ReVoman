/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.ledger

/**
 * One ledgered step: which env keys it produced + a fingerprint of its producer definition, plus
 * the keys it consumed (read via `{{key}}`). [consumed] is provenance/transparency only — it does
 * NOT gate the skip decision (that is [produces] + [hash]); it yields a self-documenting
 * producer→consumer graph. Last positionally + defaulted so the historical `(produces, hash)`
 * constructor stays source-compatible.
 */
data class LedgerEntry
@JvmOverloads
constructor(
  @JvmField val produces: Set<String>,
  @JvmField val hash: String,
  // `@JvmOverloads` keeps the historical 2-arg `LedgerEntry(produces, hash)` constructor visible to
  // Java callers — a Kotlin default alone only telescopes for Kotlin callers, not Java.
  @JvmField val consumed: Set<String> = emptySet(),
)

/** What revUp consults on a warm run: per-step entries (keyed on `Step.path`) + produced values. */
data class LedgerSnapshot(
  @JvmField val orgId: String?,
  @JvmField val steps: Map<String, LedgerEntry>,
  @JvmField val values: Map<String, String?>,
) {
  companion object {
    @JvmField val EMPTY = LedgerSnapshot(null, emptyMap(), emptyMap())
  }
}

/**
 * The full ledger file: postman-env `values` (importable) + the `x-revoman-ledger` sibling. [name]
 * is the postman-env name; [values] are the produced-key values; [orgId]/[steps] are the sibling
 * metadata.
 */
data class LedgerFile(
  @JvmField val name: String?,
  @JvmField val values: Map<String, String?>,
  @JvmField val orgId: String?,
  @JvmField val steps: Map<String, LedgerEntry>,
) {
  fun toSnapshot(): LedgerSnapshot = LedgerSnapshot(orgId, steps, values)

  companion object {
    @JvmField val EMPTY = LedgerFile(null, emptyMap(), null, emptyMap())
  }
}
