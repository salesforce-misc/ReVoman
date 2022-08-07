package org.revcloud.postman

import com.github.underscore.U
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import org.revcloud.postman.state.Request

internal class PostmanAPI {
  @JvmField
  val environment: PostmanEnvironment = PostmanEnvironment()
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

internal data class PostmanEnvironment(private val environment: MutableMap<String, String?> = mutableMapOf()) :
  MutableMap<String, String?> by environment {
  fun set(key: String, value: String?) {
    environment[key] = value
  }

  @Suppress("unused")
  fun unset(key: String) {
    environment.remove(key)
  }
}

@FunctionalInterface
internal fun interface Xml2Json {
  @Suppress("unused")
  fun xml2Json(xml: String): Map<*, *>?
}

@JsonClass(generateAdapter = true)
data class Response(val code: String, val status: String, val body: String)
