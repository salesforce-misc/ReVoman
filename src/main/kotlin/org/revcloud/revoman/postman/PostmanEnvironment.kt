package org.revcloud.revoman.postman

data class PostmanEnvironment(private val environment: MutableMap<String, String?> = mutableMapOf()) : MutableMap<String, String?> by environment {
  fun set(key: String, value: String?) {
    environment[key] = value
  }

  @Suppress("unused")
  fun unset(key: String) {
    environment.remove(key)
  }
  
  fun getValuesForKeysEndingWith(suffix: String): List<String?> = 
    environment.entries.asSequence().filter { it.key.endsWith(suffix) }.map { it.value }.toList()
}
