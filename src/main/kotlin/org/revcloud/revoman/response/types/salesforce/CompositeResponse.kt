package org.revcloud.revoman.response.types.salesforce

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CompositeResponse(val compositeResponse: List<CompositeResponseX>) {
  fun getRecordsWithReferenceId(referenceId: String): List<Map<String, Any>> =
    this.compositeResponse
      .asSequence()
      .filter { referenceId == it.referenceId }
      .flatMap { it.body.records }
      .toList()
}

@JsonClass(generateAdapter = true)
data class CompositeResponseX(
  val body: Body,
  val httpHeaders: HttpHeaders,
  val httpStatusCode: Int,
  val referenceId: String
)

@JsonClass(generateAdapter = true)
data class Body(
  val done: Boolean,
  val records:
    List<Map<String, Any>>, // ! TODO 24/06/23 gopala.akshintala: Use Record type with a Factory
  val totalSize: Int
)

@JsonClass(generateAdapter = true)
data class Record(val attributes: Attributes, val recordBody: Map<String, Any>)

@JsonClass(generateAdapter = true) class HttpHeaders

@JsonClass(generateAdapter = true) data class Attributes(val type: String, val url: String)
