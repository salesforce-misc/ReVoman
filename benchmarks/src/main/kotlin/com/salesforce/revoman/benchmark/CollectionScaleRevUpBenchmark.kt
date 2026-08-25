package com.salesforce.revoman.benchmark

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
open class CollectionScaleRevUpBenchmark {
  @Param("1", "10", "100", "500") var stepCount: String = "1"

  private lateinit var collection: PreparedCollection

  @Setup
  fun setUp() {
    collection = prepareCollection(stepCount.toInt())
    collection.validate()
    System.setProperty("revoman.banner", "off")
    revUp(collection).validate(collection.expectedStepCount)
  }

  @Benchmark
  fun revUpByStepCount(blackhole: Blackhole) {
    blackhole.consume(revUp(collection))
  }
}
