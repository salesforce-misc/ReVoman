package org.revcloud.revoman.postman.state

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
internal data class Environment(val values: List<EnvValue>)

@JsonClass(generateAdapter = true)
internal data class EnvValue(
  val key: String,
  val value: String?,
  val enabled: Boolean
)
