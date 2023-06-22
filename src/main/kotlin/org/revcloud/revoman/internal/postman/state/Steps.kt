package org.revcloud.revoman.internal.postman.state

import com.squareup.moshi.JsonClass
import org.http4k.core.ContentType
import org.http4k.core.Method
import org.http4k.core.Uri
import org.http4k.core.queryParametersEncoded
import org.http4k.core.with

@JsonClass(generateAdapter = true)
internal data class Steps(val item: List<MutableMap<String, Any>>)

@JsonClass(generateAdapter = true)
internal data class Item(val name: String = "", val request: Request = Request(), val event: List<Event>? = null)

@JsonClass(generateAdapter = true)
internal data class Script(val exec: List<String>)

@JsonClass(generateAdapter = true)
internal data class Header(val key: String, val value: String)

@JsonClass(generateAdapter = true)
internal data class Url(val raw: String = "")

@JsonClass(generateAdapter = true)
internal data class Body(val mode: String, val raw: String)

@JsonClass(generateAdapter = true)
internal data class Event(val listen: String, val script: Script)

@JsonClass(generateAdapter = true)
internal data class Request(
    val method: String = "",
    val header: List<Header> = emptyList(),
    val url: Url = Url(),
    val body: Body? = null,
    val event: List<Event>? = null
) {
  internal fun toHttpRequest(): org.http4k.core.Request {
    val contentType =
      header.firstOrNull { it.key.equals(org.http4k.lens.Header.CONTENT_TYPE.meta.name, ignoreCase = true) }
        ?.value?.let { ContentType.Text(it) } ?: ContentType.APPLICATION_JSON
    val uri = Uri.of(url.raw).queryParametersEncoded()
    return org.http4k.core.Request(Method.valueOf(method), uri)
      .with(org.http4k.lens.Header.CONTENT_TYPE of contentType)
      .headers(header.map { it.key to it.value })
      .body(body?.raw ?: "")
  }
}
