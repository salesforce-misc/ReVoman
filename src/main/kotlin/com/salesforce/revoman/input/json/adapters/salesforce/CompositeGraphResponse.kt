/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json.adapters.salesforce

import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse.Graph.ErrorGraph
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse.Graph.ErrorGraph.ErrorGraphResponse.CompositeErrorResponse
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse.Graph.ErrorGraph.ErrorGraphResponse.CompositeErrorResponse.Body
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse.Graph.SuccessGraph
import com.salesforce.revoman.input.json.factories.DiMorphicAdapter
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

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
      val graphResponse: ErrorGraphResponse,
      override val isSuccessful: Boolean,
    ) : Graph {
      @Json(ignore = true)
      @get:JvmName("errorResponses")
      val errorResponses: List<CompositeErrorResponse> by lazy {
        graphResponse.compositeResponse.filter {
          it.httpStatusCode !in SUCCESSFUL_HTTP_STATUSES &&
            it.body.firstOrNull()?.let { error ->
              error.errorCode == PROCESSING_HALTED ||
                error.message == OPERATION_IN_TRANSACTION_FAILED_ERROR
            } != true
        }
      }

      @Json(ignore = true)
      @get:JvmName("firstErrorReferenceId")
      val firstErrorReferenceId: String? by lazy { errorResponses.firstOrNull()?.referenceId }
      
      @Json(ignore = true)
      @get:JvmName("firstErrorResponse")
      val firstErrorResponse: CompositeErrorResponse? by lazy { errorResponses.firstOrNull() }

      @Json(ignore = true)
      @get:JvmName("firstErrorResponseBody")
      val firstErrorResponseBody: Body? by lazy {
        errorResponses.firstOrNull()?.body?.firstOrNull()
      }

      @JsonClass(generateAdapter = true)
      data class ErrorGraphResponse(val compositeResponse: List<CompositeErrorResponse>) {
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
        { it.nextBoolean() },
        SuccessGraph::class.java,
        ErrorGraph::class.java,
      )
  }
}
