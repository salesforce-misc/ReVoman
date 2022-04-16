package org.revcloud.state.collection

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Url(val raw: String = "")
