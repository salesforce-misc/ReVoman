/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.salesforce.revoman.input.config.RunbookStep
import com.salesforce.revoman.output.postman.PostmanEnvironment

/**
 * A step's data-flow contract breach. Empty sets/map = satisfied. `valueMismatches` maps a key to
 * `expected to actual`.
 */
internal data class ContractViolation(
  val missingConsumed: Set<String> = emptySet(),
  val missingProduced: Set<String> = emptySet(),
  val valueMismatches: Map<String, Pair<String?, String?>> = emptyMap(),
)

internal fun ContractViolation.isEmpty(): Boolean =
  missingConsumed.isEmpty() && missingProduced.isEmpty() && valueMismatches.isEmpty()

/**
 * Subset/at-least: the declared consume keys absent from [envKeys]. Extras in the env are ignored.
 */
internal fun checkConsumes(step: RunbookStep, envKeys: Set<String>): Set<String> =
  step.consumes - envKeys

/** Subset/at-least on produced keys, plus value equality for declared key→value entries. */
internal fun checkProduces(step: RunbookStep, env: PostmanEnvironment<Any?>): ContractViolation {
  val missing = step.produces.keys.filterNot { env.containsKey(it) }.toSet()
  val mismatches =
    step.produces
      .filterValues { it != null }
      .filterKeys { it !in missing }
      .mapNotNull { (key, expected) ->
        val actual = env.getAsString(key)
        if (actual == expected) null else key to (expected to actual)
      }
      .toMap()
  return ContractViolation(missingProduced = missing, valueMismatches = mismatches)
}
