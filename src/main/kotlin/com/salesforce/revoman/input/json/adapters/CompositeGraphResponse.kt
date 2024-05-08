package com.salesforce.revoman.input.json.adapters

import com.salesforce.revoman.input.json.adapters.CompositeGraphResponse.Graph.ErrorGraph
import com.salesforce.revoman.input.json.adapters.CompositeGraphResponse.Graph.ErrorGraph.GraphErrorResponse.CompositeErrorResponse
import com.salesforce.revoman.input.json.adapters.CompositeGraphResponse.Graph.ErrorGraph.GraphErrorResponse.CompositeErrorResponse.Body
import com.salesforce.revoman.input.json.adapters.CompositeGraphResponse.Graph.SuccessGraph
import com.salesforce.revoman.input.json.factories.DiMorphicAdapter
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

private const val PROCESSING_HALTED = "PROCESSING_HALTED"

@JsonClass(generateAdapter = true)
data class CompositeGraphResponse(val graphs: List<Graph>) {
  sealed interface Graph {
    val graphId: String
    val isSuccessful: Boolean

    @JsonClass(generateAdapter = true)
    data class SuccessGraph(
      override val graphId: String,
      val graphResponse: GraphResponse,
      override val isSuccessful: Boolean
    ) : Graph {
      @JsonClass(generateAdapter = true)
      data class GraphResponse(val compositeResponse: List<CompositeResponse>) {
        @JsonClass(generateAdapter = true)
        data class CompositeResponse(
          val body: Body,
          val httpHeaders: HttpHeaders,
          val httpStatusCode: Int,
          val referenceId: String
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
      override val isSuccessful: Boolean
    ) : Graph {
      @Json(ignore = true)
      @JvmField
      val errorResponses: List<CompositeErrorResponse> =
        graphResponse.compositeResponse.filter {
          it.body.firstOrNull()?.errorCode != PROCESSING_HALTED
        }
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
          val referenceId: String
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
        ErrorGraph::class.java
      )
  }
}
