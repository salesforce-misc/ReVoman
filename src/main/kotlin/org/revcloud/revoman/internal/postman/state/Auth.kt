package org.revcloud.revoman.internal.postman.state

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
internal data class Auth(val bearer: List<Bearer>, val type: String)

@JsonClass(generateAdapter = true)
internal data class Bearer(val key: String, val type: String, val value: String)
