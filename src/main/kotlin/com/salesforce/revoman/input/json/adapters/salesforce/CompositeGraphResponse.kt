/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json.adapters.salesforce

import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse.Graph.ErrorGraph
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse.Graph.ErrorGraph.GraphErrorResponse.CompositeErrorResponse
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse.Graph.ErrorGraph.GraphErrorResponse.CompositeErrorResponse.Body
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse.Graph.SuccessGraph
import com.salesforce.revoman.input.json.factories.DiMorphicAdapter
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

private val CLIENT_ERROR = 400..499
private const val PROCESSING_HALTED = "PROCESSING_HALTED"
private const val OPERATION_IN_TRANSACTION_FAILED_ERROR =
  "The transaction was rolled back since another operation in the same transaction failed."

@JsonClass(generateAdapter = true)
data class CompositeGraphResponse(val graphs: List<Graph>) {
  sealed interface Graph {
    val graphId: String
    val isSuccessful: Boolean

    @JsonClass(generateAdapter = true)
    data class SuccessGraph(
      override val graphId: String,
      val graphResponse: GraphResponse,
      override val isSuccessful: Boolean,
    ) : Graph {
      @JsonClass(generateAdapter = true)
      data class GraphResponse(val compositeResponse: List<CompositeResponse>) {
        @JsonClass(generateAdapter = true)
        data class CompositeResponse(
          val body: Body,
          val httpHeaders: HttpHeaders,
          val httpStatusCode: Int,
          val referenceId: String,
        ) {
          @JsonClass(generateAdapter = true)
          data class Body(val errors: List<Any>, val id: String, val success: Boolean)

          @JsonClass(generateAdapter = true)
          data class HttpHeaders(@Json(name = "Location") val location: String)
        }
      }
    }

    @JsonClass(generateAdapter = true)
    data class ErrorGraph(
      override val graphId: String,
      val graphResponse: GraphErrorResponse,
      override val isSuccessful: Boolean,
    ) : Graph {
      @Json(ignore = true)
      @JvmField
      val errorResponses: List<CompositeErrorResponse> =
        graphResponse.compositeResponse.filter {
          it.httpStatusCode in CLIENT_ERROR &&
            it.body.firstOrNull()?.let { error ->
              error.errorCode == PROCESSING_HALTED ||
                error.message == OPERATION_IN_TRANSACTION_FAILED_ERROR
            } != true
        }

      @Json(ignore = true)
      @JvmField
      val firstErrorResponse: CompositeErrorResponse? = errorResponses.firstOrNull()

      @Json(ignore = true)
      @JvmField
      val firstErrorResponseBody: Body? = errorResponses.firstOrNull()?.body?.firstOrNull()

      @JsonClass(generateAdapter = true)
      data class GraphErrorResponse(val compositeResponse: List<CompositeErrorResponse>) {
        @JsonClass(generateAdapter = true)
        data class CompositeErrorResponse(
          val body: List<Body>,
          val httpHeaders: HttpHeaders,
          val httpStatusCode: Int,
          val referenceId: String,
        ) {
          @JsonClass(generateAdapter = true)
          data class Body(val errorCode: String, val fields: List<Any>?, val message: String)

          @JsonClass(generateAdapter = true) class HttpHeaders
        }
      }
    }
  }

  companion object {
    @JvmField
    val ADAPTER =
      DiMorphicAdapter.of(
        Graph::class.java,
        "isSuccessful",
        true,
        SuccessGraph::class.java,
        ErrorGraph::class.java,
      )
  }
}
