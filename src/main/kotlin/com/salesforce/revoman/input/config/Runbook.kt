/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.salesforce.revoman.output.log.RunLogSink
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
class Runbook
internal constructor(
  val name: String?,
  val steps: List<RunbookStep>,
  val runLogSink: RunLogSink,
  /**
   * When true (default), a step whose underlying collection has an un-ignored failure halts the
   * runbook (throws [AssertionError]) like a contract breach. When false, the step is marked FAILED
   * in the log/views and the runbook continues — the caller inspects the returned
   * [com.salesforce.revoman.output.RunbookRundown] (mirrors base `revUp(List<Kick>)`).
   */
  val haltOnStepFailure: Boolean,
) {
  companion object {
    @JvmStatic fun configure(): RunbookBuilder = RunbookBuilder()
  }
}

/**
 * Fluent builder backing both language front doors. Each configurable field has exactly ONE storage
 * slot funneled through a fluent method (Java) and a receiver `var` extension (the Kotlin DSL, e.g.
 * [RunbookBuilder.runLogSink]/[RunbookBuilder.haltOnStepFailure] below) — never a public raw field.
 */
@RunbookDsl
class RunbookBuilder internal constructor() {
  private var name: String? = null
  private val steps: MutableList<RunbookStep> = mutableListOf()
  internal var runLogSinkValue: RunLogSink = RunLogSink.NoOp
  internal var haltOnStepFailureValue: Boolean = true

  /** Sets the runbook's human-readable [Runbook.name]. */
  fun name(name: String): RunbookBuilder = apply { this.name = name }

  /**
   * Sets the runbook-scope [RunLogSink]. Its child per-request events nest under this runbook's
   * brackets so the whole run renders as one coherent tree.
   */
  fun runLogSink(sink: RunLogSink): RunbookBuilder = apply { this.runLogSinkValue = sink }

  /**
   * When true (default), a step whose underlying collection has an un-ignored failure halts the
   * runbook (throws [AssertionError]) like a contract breach. When false, the step is marked FAILED
   * in the log/views and the runbook continues — the caller inspects the returned
   * [com.salesforce.revoman.output.RunbookRundown] (mirrors base `revUp(List<Kick>)`).
   */
  fun haltOnStepFailure(halt: Boolean): RunbookBuilder = apply {
    this.haltOnStepFailureValue = halt
  }

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

  internal fun build(): Runbook =
    Runbook(name, steps.toList(), runLogSinkValue, haltOnStepFailureValue)

  fun off(): Runbook = build()
}

/**
 * Kotlin DSL alias for [RunbookBuilder.runLogSink]: `Runbook { runLogSink = mySink }`. Funnels to
 * the same single storage the fluent method writes.
 */
var RunbookBuilder.runLogSink: RunLogSink
  get() = runLogSinkValue
  set(value) {
    runLogSinkValue = value
  }

/**
 * Kotlin DSL alias for [RunbookBuilder.haltOnStepFailure]: `Runbook { haltOnStepFailure = false }`.
 * Funnels to the same single storage the fluent method writes. Defaults to true (halt).
 */
var RunbookBuilder.haltOnStepFailure: Boolean
  get() = haltOnStepFailureValue
  set(value) {
    haltOnStepFailureValue = value
  }

/** Kotlin top-level receiver DSL entry point. */
fun Runbook(name: String? = null, block: RunbookBuilder.() -> Unit): Runbook =
  RunbookBuilder().apply { name?.let { name(it) } }.apply(block).build()

/** Kotlin `step { }` receiver — accumulates a [StepSpec] into the builder. */
fun RunbookBuilder.step(block: StepSpec.() -> Unit) {
  addSpec(StepSpec().apply(block))
}
