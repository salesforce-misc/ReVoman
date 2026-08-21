/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.impl.Log4jContextFactory
import org.apache.logging.log4j.simple.SimpleLoggerContextFactory
import org.apache.logging.log4j.status.StatusLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup

@Execution(SAME_THREAD)
class SandboxCanaryContractTest {
  @AfterEach
  fun restoreLogging() {
    LogManager.setFactory(Log4jContextFactory())
    StatusLogger.getLogger().clear()
  }

  @Test
  fun `sandbox canary initializes real Log4j core at OFF and evaluates Graal`() {
    StatusLogger.getLogger().clear()
    val canary = SandboxCanaryBenchmark()

    canary.setup()
    try {
      assertThat(canary.sandbox()).isEqualTo(42)
      assertThat(LogManager.getFactory()).isInstanceOf(Log4jContextFactory::class.java)
      val context = LogManager.getContext(false)
      assertThat(context).isInstanceOf(LoggerContext::class.java)
      assertThat((context as LoggerContext).configuration.name).isEqualTo("revoman-performance")
    } finally {
      canary.tearDown()
    }
  }

  @Test
  fun `sandbox canary rejects the SimpleLogger fallback`() {
    LogManager.setFactory(SimpleLoggerContextFactory())

    val failure = assertThrows<IllegalStateException> { SandboxCanaryBenchmark().setup() }

    assertThat(failure).hasMessageThat().contains("Log4j core")
  }

  @Test
  fun `sandbox canary rejects Log4j status errors`() {
    StatusLogger.getLogger().clear()
    StatusLogger.getLogger().error("intentional status failure")

    val failure = assertThrows<IllegalStateException> { SandboxCanaryBenchmark().setup() }

    assertThat(failure).hasMessageThat().contains("intentional status failure")
  }

  @Test
  fun `V3 adapters are thin single shot one thread one operation states`() {
    assertAdapter(RevUpV3ColdBenchmark::class.java)
    assertAdapter(RevUpV3WarmBenchmark::class.java)
  }

  @Test
  fun `profile documents own forks warmups and measurements`() {
    listOf(RevUpV3ColdBenchmark::class.java, RevUpV3WarmBenchmark::class.java).forEach { type ->
      assertThat(type.getAnnotation(Fork::class.java)).isNull()
      assertThat(type.getAnnotation(Warmup::class.java)).isNull()
      assertThat(type.getAnnotation(Measurement::class.java)).isNull()
    }

    assertThat(read("config/performance/profiles/canary.json")).contains("\"forks\": 1")
    assertThat(read("config/performance/profiles/cold.json")).contains("\"forks\": 40")
    val warm = read("config/performance/profiles/warm.json")
    assertThat(warm).contains("\"warmupIterations\": 5")
    assertThat(warm).contains("\"measurementIterations\": 10")
  }

  @Test
  fun `required cells use only sandbox and real wire revUp methods`() {
    val cells = read("config/performance/expected-cells.json")

    assertThat(cells).contains("SandboxCanaryBenchmark.sandbox")
    assertThat(cells).contains("RevUpV3ColdBenchmark.revUp")
    assertThat(cells).contains("RevUpV3WarmBenchmark.revUp")
    assertThat(cells).doesNotContain("RevUpV3ColdBenchmark.canary")
    assertThat(cells).doesNotContain("RevUpV3ColdBenchmark.cold")
    assertThat(cells).doesNotContain("RevUpV3WarmBenchmark.warm")
  }

  private fun assertAdapter(type: Class<*>) {
    assertThat(type.getAnnotation(BenchmarkMode::class.java).value.toList())
      .containsExactly(Mode.SingleShotTime)
    assertThat(type.getAnnotation(OutputTimeUnit::class.java).value)
      .isEqualTo(TimeUnit.MILLISECONDS)
    assertThat(type.getAnnotation(Threads::class.java).value).isEqualTo(1)
    assertThat(type.getAnnotation(OperationsPerInvocation::class.java).value).isEqualTo(1)
    assertThat(
        type.declaredFields
          .single { it.name == "scenario" }
          .getAnnotation(Param::class.java)
          .value
          .toList()
      )
      .containsExactly("v3-real-wire")
    assertThat(type.getDeclaredMethod("revUp").getAnnotation(Benchmark::class.java)).isNotNull()
    assertThat(type.getDeclaredMethod("setup").getAnnotation(Setup::class.java).value)
      .isEqualTo(Level.Trial)
    assertThat(type.getDeclaredMethod("verifyInvocation").getAnnotation(TearDown::class.java).value)
      .isEqualTo(Level.Invocation)
    assertThat(type.getDeclaredMethod("close").getAnnotation(TearDown::class.java).value)
      .isEqualTo(Level.Trial)
  }

  private fun read(path: String): String = Files.readString(Path.of(path))
}
