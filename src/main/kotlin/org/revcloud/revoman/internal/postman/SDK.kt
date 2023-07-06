package org.revcloud.revoman.internal.postman

import com.github.underscore.U
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import org.revcloud.revoman.internal.postman.state.Request
import org.revcloud.revoman.postman.PostmanEnvironment

internal class PostmanAPI {
  @JvmField val environment: PostmanEnvironment = PostmanEnvironment()
  lateinit var request: Request
  lateinit var response: Response

  @Suppress("unused")
  fun setEnvironmentVariable(key: String, value: String) {
    environment.set(key, value)
  }

  @OptIn(ExperimentalStdlibApi::class)
  @JvmField
  val xml2Json = Xml2Json { xml ->
    Moshi.Builder().build().adapter<Map<*, *>>().fromJson(U.xmlToJson(xml))
  }
}

@SuppressWarnings("kotlin:S6517")
@FunctionalInterface // DON'T REMOVE THIS. Polyglot won't work without this
internal fun interface Xml2Json {
  @Suppress("unused") fun xml2Json(xml: String): Map<*, *>?
}

@JsonClass(generateAdapter = true)
data class Response(val code: String, val status: String, val body: String)
