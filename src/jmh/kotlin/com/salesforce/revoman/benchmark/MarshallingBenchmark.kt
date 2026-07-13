/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse
import com.salesforce.revoman.input.json.jsonToPojo
import com.salesforce.revoman.input.json.pojoToJson
import io.vavr.control.Either
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
open class MarshallingBenchmark {

  // Representative composite query response: polymorphic elements (SuccessResponse/ErrorResponse),
  // records with attributes, nested bodies -> exercises DiMorphicAdapter (B3) + the default adapter
  // stack (B1 enum factory, B2 epoch adapter) via the memoized MoshiReVoman (B5).
  private val compositeJson =
    """
    {
      "compositeResponse": [
        {"referenceId":"ok1","httpStatusCode":200,"httpHeaders":{},
         "body":{"done":true,"totalSize":1,"records":[
           {"attributes":{"type":"Account","url":"/services/data/v58.0/sobjects/Account/001"},
            "Id":"001xx000003DGbXXXX","Name":"Acme","CreatedDate":"2015-09-01T00:00:00.000+0000"}]}},
        {"referenceId":"bad1","httpStatusCode":400,"httpHeaders":{},
         "body":[{"errorCode":"INVALID","message":"Invalid reference specified"}]}
      ]
    }
    """
      .trimIndent()

  private lateinit var composite: CompositeResponse

  @Setup
  fun setup() {
    composite =
      jsonToPojo<CompositeResponse>(
        CompositeResponse::class.java,
        compositeJson,
        customAdaptersWithType =
          mapOf(CompositeResponse.Response::class.java to Either.right(CompositeResponse.ADAPTER)),
      )!!
  }

  @Benchmark
  fun compositeFromJson(bh: Blackhole) {
    bh.consume(
      jsonToPojo<CompositeResponse>(
        CompositeResponse::class.java,
        compositeJson,
        customAdaptersWithType =
          mapOf(CompositeResponse.Response::class.java to Either.right(CompositeResponse.ADAPTER)),
      )
    )
  }

  @Benchmark
  fun compositeToJson(bh: Blackhole) {
    bh.consume(
      pojoToJson<CompositeResponse>(
        CompositeResponse::class.java,
        composite,
        customAdaptersWithType =
          mapOf(CompositeResponse.Response::class.java to Either.right(CompositeResponse.ADAPTER)),
      )
    )
  }
}
