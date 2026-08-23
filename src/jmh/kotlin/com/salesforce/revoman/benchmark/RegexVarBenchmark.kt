/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.PostmanVariableScopes
import com.salesforce.revoman.internal.postman.RegexReplacer
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.runtime.RundownProgress
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class RegexVarBenchmark {

  private lateinit var regexReplacer: RegexReplacer
  private lateinit var environment: PostmanEnvironment<Any?>

  // ~90% of real strings carry no placeholder (headers, static URL segments, literal body fields)
  // -> C1's fast-path guard should dominate the win here.
  private val mixedStrings: List<String> =
    (0 until 100).map { i ->
      if (i % 10 == 0) $$"""{ "id": "{{policyId}}", "when": "{{$isoTimestamp}}" }"""
      else """{ "field$i": "static-value-$i", "note": "no placeholders in this line" }"""
    }

  @Setup
  fun setup() {
    val moshi = initMoshi()
    environment = PostmanEnvironment(mutableMapOf(), moshi)
    val collectionVariables = PostmanEnvironment<Any?>(mutableMapOf(), moshi)
    val globals = PostmanEnvironment<Any?>(mutableMapOf(), moshi)
    val scopes = PostmanVariableScopes(environment, collectionVariables, globals, null)
    val progress = RundownProgress()
    val step = Step(index = "benchmark", rawPMStep = Item(name = "benchmark"))
    val report = StepReport(step = step, pmEnvSnapshot = environment)
    progress.begin(
      report,
      Rundown(
        stepReports = listOf(report),
        mutableEnv = environment,
        haltOnFailureOfTypeExcept = emptyMap(),
        providedStepsToExecuteCount = 1,
        collectionVariables = collectionVariables,
        globals = globals,
      ),
    )
    regexReplacer = RegexReplacer(scopes, progress, emptyMap())
    environment["policyId"] = "0Pol000000000001"
    // Large env: mostly static entries + a few placeholder entries (exercises C2 static skip).
    (0 until 500).forEach { i ->
      if (i % 25 == 0) environment["k$i"] = "prefix-{{policyId}}-suffix"
      else environment["k$i"] = "static-value-$i"
    }
  }

  @Benchmark
  fun replaceVariablesRecursivelyOverMixedStrings(bh: Blackhole) {
    mixedStrings.forEach { bh.consume(regexReplacer.replaceVariablesRecursively(it)) }
  }

  @Benchmark
  fun replaceVariablesInEnvOverLargeEnv(bh: Blackhole) {
    bh.consume(regexReplacer.replaceVariablesInEnv())
  }
}
