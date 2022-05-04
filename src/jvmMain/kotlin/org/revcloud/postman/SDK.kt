package org.revcloud.postman

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
}

data class PostmanEnvironment(private val environment: MutableMap<String, String?> = mutableMapOf()): MutableMap<String, String?> by environment {
  fun set(key: String, value: String?) {
    environment[key] = value
  }
}
