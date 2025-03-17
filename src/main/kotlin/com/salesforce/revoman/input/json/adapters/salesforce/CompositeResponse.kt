package com.salesforce.revoman.input.json.adapters.salesforce

import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse.Response.ErrorResponse
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse.Response.ErrorResponse.ErrorBody
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse.Response.SuccessResponse
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse.Response.SuccessResponse.Body.Record
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse.Response.SuccessResponse.Body.Record.Attributes
import com.salesforce.revoman.input.json.factories.DiMorphicAdapter
import com.salesforce.revoman.input.json.mapW
import com.salesforce.revoman.input.json.objW
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.rawType
import dev.zacsweers.moshix.adapters.AdaptedBy
import java.lang.reflect.Type

@JsonClass(generateAdapter = true)
data class CompositeResponse(val compositeResponse: List<Response>) {

  sealed interface Response {
    val httpStatusCode: Int
    val referenceId: String
    val httpHeaders: HttpHeaders

    @JsonClass(generateAdapter = true)
    data class SuccessResponse(
      val body: Body,
      override val httpHeaders: HttpHeaders,
      override val httpStatusCode: Int,
      override val referenceId: String,
    ) : Response {
      @JsonClass(generateAdapter = true)
      data class Body(val done: Boolean, val records: List<Record>, val totalSize: Int) {
        @JsonClass(generateAdapter = true)
        @AdaptedBy(RecordFactory::class)
        data class Record(val attributes: Attributes, val recordBody: Map<String, Any?>) {
          @JsonClass(generateAdapter = true)
          data class Attributes(val type: String, val url: String)
        }
      }

      class RecordFactory : JsonAdapter.Factory {
        override fun create(
          type: Type,
          annotations: Set<Annotation>,
          moshi: Moshi,
        ): JsonAdapter<*>? {
          return if (type.rawType == Record::class.java) {
            RecordAdapter(moshi)
          } else {
            null
          }
        }

        class RecordAdapter(val moshi: Moshi) : JsonAdapter<Record>() {
          private val options = JsonReader.Options.of("attributes")
          @OptIn(ExperimentalStdlibApi::class)
          private val attributesJsonAdapter = moshi.adapter<Attributes>()
          @OptIn(ExperimentalStdlibApi::class) private val dynamicJsonAdapter = moshi.adapter<Any>()

          override fun fromJson(reader: JsonReader): Record? {
            reader.beginObject()
            var attributes: Attributes? = null
            val recordBody = mutableMapOf<String, Any?>()
            while (reader.hasNext()) {
              when (reader.selectName(options)) {
                0 -> {
                  if (attributes != null) {
                    throw JsonDataException("Duplicate attributes Node")
                  }
                  attributes = attributesJsonAdapter.fromJson(reader)
                }
                -1 -> recordBody[reader.nextName()] = reader.readJsonValue()!!
                else -> throw AssertionError()
              }
            }
            reader.endObject()
            return Record(attributes!!, recordBody)
          }

          override fun toJson(writer: JsonWriter, record: Record?) =
            with(writer) {
              objW(record) {
                name("attributes")
                attributesJsonAdapter.toJson(this@with, attributes)
                mapW(recordBody, dynamicJsonAdapter)
              }
            }
        }
      }
    }

    @JsonClass(generateAdapter = true)
    data class ErrorResponse(
      val body: List<ErrorBody>,
      override val httpHeaders: HttpHeaders,
      override val httpStatusCode: Int,
      override val referenceId: String,
    ) : Response {
      @JsonClass(generateAdapter = true)
      data class ErrorBody(val errorCode: String, val message: String)
    }

    @JsonClass(generateAdapter = true) class HttpHeaders
  }

  @Json(ignore = true)
  @get:JvmName("errorResponses")
  val errorResponses: List<ErrorResponse> by lazy {
    compositeResponse
      .mapNotNull { it as? ErrorResponse }
      .filter {
        it.httpStatusCode !in SUCCESSFUL_HTTP_STATUSES &&
          it.body.firstOrNull()?.let { error ->
            error.errorCode == PROCESSING_HALTED &&
              error.message == OPERATION_IN_TRANSACTION_FAILED_ERROR
          } != true
      }
  }

  @Json(ignore = true)
  @get:JvmName("isSuccessful")
  val isSuccessful: Boolean by lazy {
    compositeResponse.all { it.httpStatusCode in SUCCESSFUL_HTTP_STATUSES }
  }

  @Json(ignore = true)
  @get:JvmName("errorResponseCount")
  val errorResponseCount: Int by lazy {
    compositeResponse.count { it.httpStatusCode !in SUCCESSFUL_HTTP_STATUSES }
  }

  @Json(ignore = true)
  @get:JvmName("successResponseCount")
  val successResponseCount: Int by lazy {
    compositeResponse.count { it.httpStatusCode in SUCCESSFUL_HTTP_STATUSES }
  }

  @Json(ignore = true)
  @get:JvmName("firstErrorResponse")
  val firstErrorResponse: ErrorResponse? by lazy { errorResponses.firstOrNull() }

  @Json(ignore = true)
  @get:JvmName("firstErrorResponseBody")
  val firstErrorResponseBody: ErrorBody? by lazy {
    errorResponses.firstOrNull()?.body?.firstOrNull()
  }

  companion object {
    @JvmField
    val ADAPTER =
      DiMorphicAdapter.of(
        Response::class.java,
        "httpStatusCode",
        { it.nextInt() in SUCCESSFUL_HTTP_STATUSES },
        SuccessResponse::class.java,
        ErrorResponse::class.java,
      )
  }
}
