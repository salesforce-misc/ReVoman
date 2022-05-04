package org.revcloud.postman.state.environment

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Environment(val values: List<EnvValue>)
