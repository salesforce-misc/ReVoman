package com.salesforce.revoman.benchmark

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
open class EnvironmentScaleRevUpBenchmark {
  @Param("0", "10", "100", "1000") var environmentSize: String = "0"

  private lateinit var collection: PreparedCollection
  private lateinit var environment: Map<String, Any?>

  @Setup
  fun setUp() {
    collection = prepareCollection(stepCount = 10)
    collection.validate()
    environment = prepareEnvironment(environmentSize.toInt())
    check(environment.size == environmentSize.toInt())
    System.setProperty("revoman.banner", "off")
    revUp(collection, environment).validate(collection.expectedStepCount)
  }

  @Benchmark
  fun revUpByEnvironmentSize(blackhole: Blackhole) {
    blackhole.consume(revUp(collection, environment))
  }
}
