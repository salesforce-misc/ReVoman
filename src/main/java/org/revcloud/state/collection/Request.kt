package org.revcloud.state.collection

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Request(
  val method: String = "",
  val header: List<Header> = emptyList(),
  val url: Url = Url(),
  val body: Body? = null,
  val event: List<Event>? = null
)
