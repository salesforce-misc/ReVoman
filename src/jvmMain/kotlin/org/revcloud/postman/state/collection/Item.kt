package org.revcloud.postman.state.collection

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Item(val name: String = "", val request: Request = Request(), val event: List<Event>? = null)
