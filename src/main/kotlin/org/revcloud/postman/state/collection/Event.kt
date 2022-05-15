package org.revcloud.postman.state.collection

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Event(val listen: String, val script: Script)
