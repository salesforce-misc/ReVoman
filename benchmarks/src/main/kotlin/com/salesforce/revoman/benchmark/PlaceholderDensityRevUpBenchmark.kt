package com.salesforce.revoman.benchmark

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
open class PlaceholderDensityRevUpBenchmark {
  @Param("1", "10") var placeholdersPerRequest: String = "1"

  private lateinit var collection: PreparedCollection
  private lateinit var environment: Map<String, Any?>

  @Setup
  fun setUp() {
    collection =
      prepareCollection(
        stepCount = 10,
        placeholdersPerRequest = placeholdersPerRequest.toInt(),
      )
    collection.validate()
    environment = prepareEnvironment(size = 100)
    System.setProperty("revoman.banner", "off")
    revUp(collection, environment).validate(collection.expectedStepCount)
  }

  @Benchmark
  fun revUpByPlaceholderDensity(blackhole: Blackhole) {
    blackhole.consume(revUp(collection, environment))
  }
}
