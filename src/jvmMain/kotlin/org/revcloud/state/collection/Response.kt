package org.revcloud.state.collection

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Response(val code: String, val status: String, val body: String)
