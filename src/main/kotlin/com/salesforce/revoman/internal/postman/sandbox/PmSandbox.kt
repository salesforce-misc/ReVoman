/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

/**
 * The single entry point the rest of ReVoman uses to run pm scripts. Wraps a [SandboxBridge] (one
 * booted GraalJS context per ReVoman run). Construct once per run; [close] at the end.
 *
 * All GraalJS/bridge/Flatted detail lives behind [execute].
 */
internal class PmSandbox : AutoCloseable {
  private val bridge = SandboxBridge()
  private var booted = false
  private var closed = false
  private var idSeq = 0L

  private fun ensureBooted() {
    if (!booted) {
      bridge.boot()
      booted = true
    }
  }

  fun execute(
    script: String,
    target: ScriptTarget,
    context: PmExecutionContext,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  ): PmExecutionResult {
    check(!closed) { "sandbox: execute() after close()" }
    ensureBooted()
    return bridge.dispatchExecute("step${idSeq++}", script, target, context, timeoutMs)
  }

  override fun close() {
    if (booted) bridge.close()
    closed = true
  }

  private companion object {
    const val DEFAULT_TIMEOUT_MS = 60_000L
  }
}

/** Keys produced (added or value-changed) and unset (removed) between two scope snapshots. */
internal data class ScopeDiff(val produced: Set<String>, val unset: Set<String>)

/**
 * Diffs a pre-execution scope snapshot against the post-execution scope returned by the sandbox.
 * `produced` = keys whose value is new or changed; `unset` = keys present before but gone after.
 * Equality uses structural `==` on the boxed values (Strings/numbers/maps from the bridge decode).
 */
internal fun diffScopes(before: Map<String, Any?>, after: Map<String, Any?>): ScopeDiff {
  val produced = after.filter { (k, v) -> !before.containsKey(k) || before[k] != v }.keys
  val unset = before.keys - after.keys
  return ScopeDiff(produced, unset)
}
