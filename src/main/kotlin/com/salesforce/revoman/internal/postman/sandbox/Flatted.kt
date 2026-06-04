/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Decodes Flatted (https://github.com/WebReflection/flatted) — the circular-safe JSON the uvm
 * bridge uses on the guest→host path. The serialized form is a JSON array of "slots"; every JSON
 * string in a slot is actually a decimal index into the slots array, and slot 0 is the root. We
 * rebuild the object graph, resolving string-indices to their referenced slot (which may be a
 * primitive string, an object, an array, or — for cycles — an ancestor already being built).
 *
 * Returns the decoded root. Bridge event payloads are always a top-level array, so callers cast to
 * `List<*>` and read `(eventName, ...args)`.
 *
 * Recursion depth tracks the object-graph nesting depth, not slot count. Bridge payloads
 * (`execution.result` and friends) are broad-but-shallow, so this is safe in practice; a future
 * iterative rewrite would be needed only if pathologically deep (thousands of levels) user JSON
 * ever flows through.
 */
internal object Flatted {
  private val slotsAdapter =
    Moshi.Builder()
      .build()
      .adapter<List<Any?>>(Types.newParameterizedType(List::class.java, Any::class.java))

  fun parse(json: String): Any? {
    val slots = slotsAdapter.fromJson(json) ?: return null
    if (slots.isEmpty()) return null
    val built = arrayOfNulls<Any?>(slots.size)
    val done = BooleanArray(slots.size)
    return resolve(0, slots, built, done)
  }

  private fun resolve(index: Int, slots: List<Any?>, built: Array<Any?>, done: BooleanArray): Any? {
    if (done[index]) return built[index]
    return when (val raw = slots[index]) {
      is String -> {
        built[index] = raw
        done[index] = true
        raw
      }
      is Map<*, *> -> {
        val out = LinkedHashMap<String, Any?>()
        built[index] = out
        done[index] = true
        for ((k, v) in raw) out[k as String] = deref(v, slots, built, done)
        out
      }
      is List<*> -> {
        val out = ArrayList<Any?>(raw.size)
        built[index] = out
        done[index] = true
        for (v in raw) out.add(deref(v, slots, built, done))
        out
      }
      else -> {
        built[index] = raw
        done[index] = true
        raw
      }
    }
  }

  private fun deref(value: Any?, slots: List<Any?>, built: Array<Any?>, done: BooleanArray): Any? =
    when (value) {
      is String -> resolve(value.toInt(), slots, built, done)
      else -> value
    }
}
