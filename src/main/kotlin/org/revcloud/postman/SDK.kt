package org.revcloud.postman

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import org.revcloud.postman.state.collection.Request
import org.revcloud.postman.state.collection.Response

internal class PostmanAPI {
  @JvmField
  val environment: PostmanEnvironment = PostmanEnvironment()
  lateinit var request: Request
  lateinit var response: Response
  
  @Suppress("unused")
  fun setEnvironmentVariable(key: String, value: String) {
    environment.set(key, value)
  }
  
  @JvmField
  val xml2Json = Xml2Json { xml -> XmlMapper().readValue(xml, Map::class.java) }
}

internal data class PostmanEnvironment(private val environment: MutableMap<String, String?> = mutableMapOf()): MutableMap<String, String?> by environment {
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
  fun xml2Json(xml: String): Map<*, *>
}

