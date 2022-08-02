package org.revcloud.postman.state.environment

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EnvValue(
  val key: String,
  val value: String?,
  val enabled: Boolean
)
