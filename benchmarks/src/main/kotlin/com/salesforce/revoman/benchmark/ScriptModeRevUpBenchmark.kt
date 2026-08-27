package com.salesforce.revoman.benchmark

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
open class ScriptModeRevUpBenchmark {
  @Param("1", "10") var stepCount: String = "1"
  @Param("script-free", "script-bearing") var scriptMode: String = "script-free"

  private lateinit var collection: PreparedCollection

  @Setup
  fun setUp() {
    check(scriptMode == "script-free" || scriptMode == "script-bearing")
    collection =
      prepareCollection(
        stepCount = stepCount.toInt(),
        includeScript = scriptMode == "script-bearing",
      )
    collection.validate()
    System.setProperty("revoman.banner", "off")
    revUp(collection).validate(collection.expectedStepCount)
  }

  @Benchmark
  fun revUpByScriptMode(blackhole: Blackhole) {
    blackhole.consume(revUp(collection))
  }
}
