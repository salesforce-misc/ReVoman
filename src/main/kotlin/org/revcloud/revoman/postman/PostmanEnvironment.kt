package org.revcloud.revoman.postman

data class PostmanEnvironment(private val environment: MutableMap<String, String?> = mutableMapOf()) : MutableMap<String, String?> by environment {
  fun set(key: String, value: String?) {
    environment[key] = value
  }

  @Suppress("unused")
  fun unset(key: String) {
    environment.remove(key)
  }
}
