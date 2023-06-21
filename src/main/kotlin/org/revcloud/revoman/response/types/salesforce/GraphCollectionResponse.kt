package org.revcloud.revoman.response.types.salesforce

import com.squareup.moshi.JsonClass
import dev.zacsweers.moshix.adapters.JsonString

@JsonClass(generateAdapter = true)
data class GraphCollectionResponse(val graphs: List<Graph>)

@JsonClass(generateAdapter = true)
data class Graph(val graphId: String, val graphResponse: GraphResponse, val isSuccessful: Boolean)

@JsonClass(generateAdapter = true)
data class GraphResponse(val compositeGraphResponse: List<CompositeGraphResponse>)

@JsonClass(generateAdapter = true)
data class CompositeGraphResponse(
  @JsonString val body: String?,
  val httpHeaders: Map<String, String>,
  val httpStatusCode: Int,
  val referenceId: String
)
