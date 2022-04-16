package org.revcloud.state

import org.revcloud.state.collection.Request
import org.revcloud.state.collection.Response

class PostmanAPI {
  @JvmField
  val environment: PostmanEnvironment = PostmanEnvironment()
  lateinit var request: Request
  lateinit var response: Response
  
  fun setEnvironmentVariable(key: String, value: String) {
    environment.set(key, value)
  }
  
}

data class PostmanEnvironment(private val environment: MutableMap<String, String> = mutableMapOf()): MutableMap<String, String> by environment {
  fun set(key: String, value: String) {
    environment[key] = value
  }
}
