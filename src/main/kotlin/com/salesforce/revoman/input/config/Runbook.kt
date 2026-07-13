/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import java.util.function.Consumer

/**
 * Marks the runbook builder receivers so an inner `step { }` cannot accidentally call
 * outer-[RunbookBuilder] methods.
 */
@DslMarker annotation class RunbookDsl

/**
 * An ordered, named chain of [RunbookStep]s — the legible form of a multi-collection test. Build
 * via the Kotlin `Runbook { step { } }` DSL or the Java `Runbook.configure()...off()` builder;
 * drive with `ReVoman.revUp(runbook)`.
 */
class Runbook internal constructor(val name: String?, val steps: List<RunbookStep>) {
  companion object {
    @JvmStatic fun configure(): RunbookBuilder = RunbookBuilder()
  }
}

/** Fluent builder backing both language front doors. */
@RunbookDsl
class RunbookBuilder internal constructor() {
  private var name: String? = null
  private val steps: MutableList<RunbookStep> = mutableListOf()

  fun name(name: String): RunbookBuilder = apply { this.name = name }

  /** Java: pure-narration step (no contract/assertion). */
  fun step(intent: String, phase: Phase, kick: Kick): RunbookBuilder =
    step(intent, phase, kick, Consumer {})

  /** Java: configured step. */
  fun step(
    intent: String,
    phase: Phase,
    kick: Kick,
    configurator: Consumer<StepSpec>,
  ): RunbookBuilder = apply {
    val spec =
      StepSpec().also {
        it.intent = intent
        it.phase = phase
        it.kick = kick
      }
    configurator.accept(spec)
    steps += spec.build()
  }

  internal fun addSpec(spec: StepSpec) = apply { steps += spec.build() }

  internal fun build(): Runbook = Runbook(name, steps.toList())

  fun off(): Runbook = build()
}

/** Kotlin top-level receiver DSL entry point. */
fun Runbook(name: String? = null, block: RunbookBuilder.() -> Unit): Runbook =
  RunbookBuilder().apply { name?.let { name(it) } }.apply(block).build()

/** Kotlin `step { }` receiver — accumulates a [StepSpec] into the builder. */
@RunbookDsl
fun RunbookBuilder.step(block: StepSpec.() -> Unit) {
  addSpec(StepSpec().apply(block))
}
