/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.ledger

/** One ledgered step: which env keys it produced + a fingerprint of its producer definition. */
data class LedgerEntry(@JvmField val produces: Set<String>, @JvmField val hash: String)

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
