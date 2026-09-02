package com.salesforce.revoman.benchmark

import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.Verbosity
import com.salesforce.revoman.output.toJson
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown

private const val EXPECTED_JAVA_FEATURE_PROPERTY = "revoman.scorecard.expectedJavaFeature"
private const val EXPECTED_JAVA_FEATURE = 25
private const val WORKFLOW_STEP_COUNT = 3

open class ConsumerJourneyBenchmark {
  @Benchmark
  fun postmanV2TenStepRevUp(state: ConsumerRevUpState, blackhole: Blackhole) {
    blackhole.consume(ReVoman.revUp(state.postmanV2TenStep))
  }

  @Benchmark
  fun v3TenStepRevUp(state: ConsumerRevUpState, blackhole: Blackhole) {
    blackhole.consume(ReVoman.revUp(state.v3TenStep))
  }

  @Benchmark
  fun v3HundredStepRevUp(state: ConsumerRevUpState, blackhole: Blackhole) {
    blackhole.consume(ReVoman.revUp(state.v3HundredStep))
  }

  @Benchmark
  fun v3TenStepScriptedRevUp(state: ConsumerRevUpState, blackhole: Blackhole) {
    blackhole.consume(ReVoman.revUp(state.v3TenStepScripted))
  }

  @Benchmark
  fun threeKickEnvironmentHandoff(state: ConsumerRevUpState, blackhole: Blackhole) {
    blackhole.consume(ReVoman.revUp(state.threeKicks))
  }

  @Benchmark
  fun threeStepRunbookWithContracts(state: ConsumerRevUpState, blackhole: Blackhole) {
    blackhole.consume(ReVoman.revUp(state.runbook))
  }

  @Benchmark
  fun verboseHundredStepRundownJson(state: VerboseRenderingState, blackhole: Blackhole) {
    blackhole.consume(state.rundown.toJson(Verbosity.VERBOSE))
  }
}

@State(Scope.Benchmark)
open class ConsumerRevUpState {
  private lateinit var prepared: PreparedConsumerJourneys

  internal val postmanV2TenStep: Kick
    get() = prepared.postmanV2TenStep

  internal val v3TenStep: Kick
    get() = prepared.v3TenStep

  internal val v3HundredStep: Kick
    get() = prepared.v3HundredStep

  internal val v3TenStepScripted: Kick
    get() = prepared.v3TenStepScripted

  internal val threeKicks: List<Kick>
    get() = prepared.threeKicks

  internal val runbook: Runbook
    get() = prepared.runbook

  @Setup
  fun setUp() {
    requireJava25ScorecardRuntime()
    prepared = prepareConsumerJourneys()
    check(prepared.handlerLedger.calls().isEmpty())
    check(prepared.threeKicks.size == WORKFLOW_STEP_COUNT)
    check(prepared.runbook.steps.size == WORKFLOW_STEP_COUNT)
  }

  @TearDown fun tearDown() = prepared.close()
}

@State(Scope.Benchmark)
open class VerboseRenderingState {
  private lateinit var prepared: PreparedVerboseRendering

  internal val rundown: Rundown
    get() = prepared.rundown

  @Setup
  fun setUp() {
    requireJava25ScorecardRuntime()
    prepared = prepareVerboseRendering()
    prepared.rundown.validate(expectedStepCount = 100)
  }

  @TearDown fun tearDown() = prepared.close()
}

private fun requireJava25ScorecardRuntime() {
  require(Runtime.version().feature() == EXPECTED_JAVA_FEATURE) {
    "Consumer scorecard requires Java 25, got ${Runtime.version().feature()}"
  }
  require(System.getProperty(EXPECTED_JAVA_FEATURE_PROPERTY) == EXPECTED_JAVA_FEATURE.toString()) {
    "Consumer scorecard requires -D$EXPECTED_JAVA_FEATURE_PROPERTY=$EXPECTED_JAVA_FEATURE"
  }
}
