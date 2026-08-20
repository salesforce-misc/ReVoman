/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.log.Banner
import com.salesforce.revoman.internal.log.RunLogContext
import com.salesforce.revoman.output.Rundown

internal fun interface ExecutionSessionFactory {
  @JvmSynthetic fun open(initialEnvironment: Map<String, Any?>): ExecutionSession
}

internal interface ExecutionSession : AutoCloseable {
  @JvmSynthetic
  fun executeKick(
    configuredKick: Kick,
    carryForward: Boolean,
    beforeCarry: ((Rundown, List<Rundown>) -> Unit)? = null,
  ): Rundown

  @JvmSynthetic override fun close()
}

@JvmSynthetic
internal fun executionSession(
  initialEnvironment: Map<String, Any?>,
  kickExecutions: KickExecutionFactory,
): ExecutionSession =
  object : ExecutionSession {
    private var carriedEnvironment: Map<String, Any?> = initialEnvironment.toMap()
    private val finalizedRundowns = mutableListOf<Rundown>()
    private var activeChild: KickExecution? = null
    private var closed = false

    override fun executeKick(
      configuredKick: Kick,
      carryForward: Boolean,
      beforeCarry: ((Rundown, List<Rundown>) -> Unit)?,
    ): Rundown {
      check(!closed) { "ExecutionSession is already closed" }
      check(activeChild == null) { "ExecutionSession is already executing a kick" }
      val effectiveDynamicEnvironment = configuredKick.dynamicEnvironment() + carriedEnvironment
      val effectiveKick = configuredKick.overrideDynamicEnvironment(effectiveDynamicEnvironment)

      Banner.onRunStart()
      val previousSink = RunLogContext.install(effectiveKick.runLogSink())
      val rundown =
        try {
          val child = kickExecutions.create(configuredKick, effectiveDynamicEnvironment)
          val completed =
            try {
              activeChild = child
              child.use { it.execute() }
            } finally {
              activeChild = null
            }
          Banner.recordSteps(completed.stepReports.size)
          completed
        } finally {
          RunLogContext.restore(previousSink)
        }

      finalizedRundowns += rundown
      val finalizedSnapshot = finalizedRundowns.toList()
      beforeCarry?.invoke(rundown, finalizedSnapshot)
      if (carryForward) carriedEnvironment = rundown.mutableEnv.o1Snapshot().mutableEnv
      return rundown
    }

    override fun close() {
      if (closed) return
      closed = true
      try {
        activeChild?.close()
      } finally {
        activeChild = null
        carriedEnvironment = emptyMap()
        finalizedRundowns.clear()
      }
    }
  }

@JvmSynthetic
internal fun executionSessionFactory(
  kickExecutions: KickExecutionFactory
): ExecutionSessionFactory = ExecutionSessionFactory { initialEnvironment ->
  executionSession(initialEnvironment, kickExecutions)
}
