/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment

/**
 * The story phase a [RunbookStep] belongs to; drives log grouping. Mirrors the comment blocks that
 * today separate setup/seed/act config constants.
 */
enum class Phase {
  SETUP,
  SEED,
  ACT,
  ASSERT,
  CLEANUP,
}

/**
 * A per-step assertion run after a step's [Rundown] is produced; receives that step's rundown and
 * the accumulated env. A thrown [AssertionError] halts the runbook. Complementary to the contract
 * checks and to the whole-run `postExeHook`.
 */
fun interface StepAssertion {
  fun assertStep(rundown: Rundown, env: PostmanEnvironment<Any?>)
}

/**
 * Mutable builder serving BOTH the Kotlin `step { }` receiver DSL and the Java `Consumer<StepSpec>`
 * configurator. Snapshot into an immutable [RunbookStep] via [build]. Deviates from the Immutables
 * convention used by [Kick] because [assertAfter] is a lambda and one spec must serve both language
 * front doors.
 */
class StepSpec {
  var intent: String = ""
  var phase: Phase = Phase.SETUP
  var kick: Kick? = null

  private val consumes: MutableSet<String> = linkedSetOf()
  private val produces: MutableMap<String, String?> = linkedMapOf()
  private var underTest: Boolean = false
  private var assertAfter: StepAssertion? = null

  fun consumes(vararg keys: String): StepSpec = apply { consumes += keys }

  fun produces(vararg keys: String): StepSpec = apply { keys.forEach { produces[it] = null } }

  fun produces(keyToValue: Map<String, String?>): StepSpec = apply { produces += keyToValue }

  fun produces(pair: Pair<String, String?>): StepSpec = apply { produces[pair.first] = pair.second }

  fun underTest(): StepSpec = apply { underTest = true }

  fun assertAfter(assertion: StepAssertion): StepSpec = apply { assertAfter = assertion }

  fun build(): RunbookStep =
    RunbookStep(
      intent = intent,
      phase = phase,
      kick =
        checkNotNull(kick) { "A runbook `step` requires a `kick` (was null for intent='$intent')" },
      consumes = consumes.toSet(),
      produces = produces.toMap(),
      underTest = underTest,
      assertAfter = assertAfter,
    )
}

/** Immutable snapshot of one runbook step: a [Kick] wrapped with narration. */
data class RunbookStep(
  val intent: String,
  val phase: Phase,
  val kick: Kick,
  val consumes: Set<String>,
  val produces: Map<String, String?>,
  val underTest: Boolean,
  val assertAfter: StepAssertion?,
)
