package org.revcloud.revoman.response.types.salesforce


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CompositeResponse(
  val compositeResponse: List<CompositeResponseX>
)

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
  val records: List<Record>,
  val totalSize: Int
)

@JsonClass(generateAdapter = true)
data class Record(
  val attributes: Attributes,
  @Json(name = "Id")
  val id: String,
  @Json(name = "Product2Id")
  val product2Id: String,
  @Json(name = "QuoteId")
  val quoteId: String
)

@JsonClass(generateAdapter = true)
class HttpHeaders

@JsonClass(generateAdapter = true)
data class Attributes(
  val type: String,
  val url: String
)
