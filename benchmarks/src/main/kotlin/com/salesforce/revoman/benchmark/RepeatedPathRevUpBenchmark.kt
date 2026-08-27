package com.salesforce.revoman.benchmark

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
open class RepeatedPathRevUpBenchmark {
  @Param("1", "10") var repetitions: String = "1"

  private lateinit var templatePath: String
  private lateinit var environment: Map<String, Any?>

  @Setup
  fun setUp() {
    templatePath = "pm-templates/v3/single-ok"
    checkNotNull(
      Thread.currentThread()
        .contextClassLoader
        .getResource("$templatePath/.resources/definition.yaml")
    )
    environment = mapOf("baseUrl" to "http://benchmark.invalid")
    System.setProperty("revoman.banner", "off")
    revUpPath(templatePath, environment).validate(expectedStepCount = 1)
  }

  @Benchmark
  fun repeatedCollectionPathRevUp(blackhole: Blackhole) {
    repeat(repetitions.toInt()) {
      blackhole.consume(revUpPath(templatePath, environment))
    }
  }
}
