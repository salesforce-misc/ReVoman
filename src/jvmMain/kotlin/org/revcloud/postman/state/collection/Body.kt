package org.revcloud.postman.state.collection

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Body(val mode: String, val raw: String)
