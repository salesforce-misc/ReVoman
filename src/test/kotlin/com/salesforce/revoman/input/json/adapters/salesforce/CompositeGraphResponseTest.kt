/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json.adapters.salesforce

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import io.vavr.control.Either
import org.junit.jupiter.api.Test

class CompositeGraphResponseTest {
  private val moshi =
    initMoshi(
      customAdaptersWithType =
        mapOf(
          CompositeGraphResponse.Graph::class.java to Either.right(CompositeGraphResponse.ADAPTER)
        )
    )

  @Test
  fun `errorResponses retains PROCESSING_HALTED error with normal message`() {
    val json =
      """
      {
          "graphs": [{
              "graphId": "graph1",
              "isSuccessful": false,
              "graphResponse": {
                  "compositeResponse": [{
                      "referenceId": "ref1",
                      "httpStatusCode": 400,
                      "httpHeaders": {},
                      "body": [{
                          "errorCode": "PROCESSING_HALTED",
                          "message": "Operation failed due to validation error",
                          "fields": []
                      }]
                  }]
              }
          }]
      }
      """
        .trimIndent()

    val response =
      moshi.fromJson<CompositeGraphResponse>(json, CompositeGraphResponse::class.java)!!
    val errorGraph = response.graphs.first() as CompositeGraphResponse.Graph.ErrorGraph

    // The error should be retained because it has PROCESSING_HALTED but NOT the rollback message
    assertThat(errorGraph.errorResponses).hasSize(1)
    assertThat(errorGraph.firstErrorResponseBody?.errorCode).isEqualTo("PROCESSING_HALTED")
    assertThat(errorGraph.firstErrorResponseBody?.message)
      .isEqualTo("Operation failed due to validation error")
  }

  @Test
  fun `errorResponses filters out PROCESSING_HALTED error with rollback message`() {
    val json =
      """
      {
          "graphs": [{
              "graphId": "graph1",
              "isSuccessful": false,
              "graphResponse": {
                  "compositeResponse": [{
                      "referenceId": "ref1",
                      "httpStatusCode": 400,
                      "httpHeaders": {},
                      "body": [{
                          "errorCode": "PROCESSING_HALTED",
                          "message": "The transaction was rolled back since another operation in the same transaction failed.",
                          "fields": []
                      }]
                  }]
              }
          }]
      }
      """
        .trimIndent()

    val response =
      moshi.fromJson<CompositeGraphResponse>(json, CompositeGraphResponse::class.java)!!
    val errorGraph = response.graphs.first() as CompositeGraphResponse.Graph.ErrorGraph

    // This error should be filtered out because it has both PROCESSING_HALTED and the rollback
    // message
    assertThat(errorGraph.errorResponses).isEmpty()
    assertThat(errorGraph.firstErrorResponseBody).isNull()
  }

  @Test
  fun `errorResponses retains error with only rollback message but different error code`() {
    val json =
      """
      {
          "graphs": [{
              "graphId": "graph1",
              "isSuccessful": false,
              "graphResponse": {
                  "compositeResponse": [{
                      "referenceId": "ref1",
                      "httpStatusCode": 400,
                      "httpHeaders": {},
                      "body": [{
                          "errorCode": "SOME_OTHER_ERROR",
                          "message": "The transaction was rolled back since another operation in the same transaction failed.",
                          "fields": []
                      }]
                  }]
              }
          }]
      }
      """
        .trimIndent()

    val response =
      moshi.fromJson<CompositeGraphResponse>(json, CompositeGraphResponse::class.java)!!
    val errorGraph = response.graphs.first() as CompositeGraphResponse.Graph.ErrorGraph

    // This error should be retained because it doesn't have PROCESSING_HALTED error code
    assertThat(errorGraph.errorResponses).hasSize(1)
    assertThat(errorGraph.firstErrorResponseBody?.errorCode).isEqualTo("SOME_OTHER_ERROR")
  }
}
