/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
@file:JvmName("ExecutionLifecycleDiagnostics")
@file:JvmSynthetic

package com.salesforce.revoman.internal.runtime

import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.Collections
import java.util.IdentityHashMap

private const val DIAGNOSTICS_PROPERTY: String = "revoman.lifecycleDiagnostics"
private const val WEAK_REFERENCES_V1: String = "weak-references-v1"
private const val EXECUTION_SESSION_TYPE: String = "ExecutionSession"
private const val KICK_EXECUTION_TYPE: String = "KickExecution"
private val enabled: Boolean = System.getProperty(DIAGNOSTICS_PROPERTY) == WEAK_REFERENCES_V1
private val recordsLock: Any? = if (enabled) Any() else null
private var records: ArrayList<Any>? = if (enabled) ArrayList() else null
private var recordCount: Long = 0

@JvmSynthetic
internal fun registerExecutionSession(value: ExecutionSession) {
  if (!enabled) return
  register(EXECUTION_SESSION_TYPE, value)
}

@JvmSynthetic
internal fun registerKickExecution(value: KickExecution) {
  if (!enabled) return
  register(KICK_EXECUTION_TYPE, value)
}

private fun register(type: String, value: Any) {
  val lock = requireNotNull(recordsLock)
  val reference = WeakReference(value)
  synchronized(lock) {
    val nextCount = Math.addExact(recordCount, 1)
    require(nextCount <= Int.MAX_VALUE.toLong()) { "Lifecycle diagnostics record count overflow" }
    val current = requireNotNull(records)
    current.ensureCapacity(Math.addExact(current.size, 2))
    current.add(type)
    current.add(reference)
    recordCount = nextCount
  }
}

@JvmName("drain")
@JvmSynthetic
internal fun drainLifecycleDiagnostics(): Array<Any> {
  if (!enabled) return emptyArray()
  val lock = requireNotNull(recordsLock)
  return synchronized(lock) {
    val current = requireNotNull(records)
    validateRecords(current, recordCount)
    val replacement = ArrayList<Any>()
    val normalized = current.toArray(arrayOfNulls<Any>(current.size)).requireNoNulls()
    records = replacement
    recordCount = 0
    normalized
  }
}

private fun validateRecords(current: ArrayList<Any>, count: Long) {
  check(count >= 0 && count <= Int.MAX_VALUE.toLong()) {
    "Lifecycle diagnostics record count is outside the supported range"
  }
  val expectedSlots = Math.multiplyExact(count, 2)
  check(expectedSlots == current.size.toLong() && current.size % 2 == 0) {
    "Lifecycle diagnostics records are malformed"
  }
  val identities = Collections.newSetFromMap(IdentityHashMap<WeakReference<*>, Boolean>())
  current.indices.step(2).forEach { index ->
    val type = current[index]
    check(type is String && type in setOf(EXECUTION_SESSION_TYPE, KICK_EXECUTION_TYPE)) {
      "Lifecycle diagnostics type is unsupported"
    }
    val reference = current[index + 1]
    check(reference is WeakReference<*> && reference.javaClass === WeakReference::class.java) {
      "Lifecycle diagnostics reference must be an exact WeakReference"
    }
    check(identities.add(reference)) { "Lifecycle diagnostics reference identity is repeated" }
  }
}
