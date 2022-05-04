package org.revcloud.postman.state.collection

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Header(val key: String, val value: String)
