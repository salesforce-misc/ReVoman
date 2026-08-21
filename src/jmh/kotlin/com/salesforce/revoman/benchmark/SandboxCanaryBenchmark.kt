/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.salesforce.revoman.internal.postman.sandbox.PmExecutionContext
import com.salesforce.revoman.internal.postman.sandbox.PmSandbox
import com.salesforce.revoman.internal.postman.sandbox.PmScope
import com.salesforce.revoman.internal.postman.sandbox.ScriptTarget
import java.net.URI
import java.util.concurrent.TimeUnit
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.impl.Log4jContextFactory
import org.apache.logging.log4j.status.StatusLogger
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level.Trial
import org.openjdk.jmh.annotations.Mode.SingleShotTime
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope.Benchmark
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads

/**
 * Structural canary for the real Log4j provider and one minimal Graal/Postman sandbox operation.
 */
@State(Benchmark)
@BenchmarkMode(SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Threads(1)
@OperationsPerInvocation(1)
open class SandboxCanaryBenchmark {
  private lateinit var sandboxEngine: PmSandbox

  @Setup(Trial)
  fun setup() {
    requirePerformanceLogging()
    sandboxEngine = PmSandbox()
  }

  @Benchmark
  fun sandbox(): Any? =
    sandboxEngine
      .execute(
        script = "pm.environment.set('answer', 40 + 2);",
        target = ScriptTarget.TEST,
        context = PmExecutionContext(environment = PmScope("benchmark", emptyMap())),
      )
      .environment["answer"]

  @TearDown(Trial)
  fun tearDown() {
    if (::sandboxEngine.isInitialized) sandboxEngine.close()
  }
}

/** Initializes and validates the benchmark-only Log4j profile before any benchmark operation. */
internal fun requirePerformanceLogging() {
  val configured =
    checkNotNull(System.getProperty(FROZEN_LOG4J_CONFIGURATION_PROPERTY)) {
      "Performance Log4j configuration property is missing"
    }
  System.setProperty(LOG4J_CONFIGURATION_PROPERTY, configured)
  val factory = LogManager.getFactory()
  check(factory is Log4jContextFactory) {
    "Sandbox canary requires Log4j core, got ${factory::class.java.name}"
  }
  val context = LogManager.getContext(false)
  check(context is LoggerContext) {
    "Sandbox canary requires the Log4j core context, got ${context::class.java.name}"
  }
  val requestedLocation = URI.create(configured)
  if (context.configLocation != requestedLocation) {
    context.configLocation = requestedLocation
  }
  check(context.configuration.name == CONFIGURATION_NAME) {
    "Sandbox canary loaded unexpected Log4j configuration '${context.configuration.name}'"
  }
  check(context.configuration.rootLogger.level == Level.OFF) {
    "Sandbox canary Log4j root level must be OFF"
  }
  val statusErrors =
    StatusLogger.getLogger().statusData.filter { it.level.isMoreSpecificThan(Level.ERROR) }
  check(statusErrors.isEmpty()) {
    "Sandbox canary observed Log4j status errors: " +
      statusErrors.joinToString(" | ") { it.formattedStatus }
  }
}

private const val CONFIGURATION_NAME = "revoman-performance"
private const val FROZEN_LOG4J_CONFIGURATION_PROPERTY = "log4j.configurationFile"
private const val LOG4J_CONFIGURATION_PROPERTY = "log4j2.configurationFile"
