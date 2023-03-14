package org.revcloud.revoman.internal

import org.apache.commons.lang3.StringUtils
import org.http4k.core.ContentType
import org.http4k.core.Response
import java.io.File

internal fun isContentTypeApplicationJson(response: Response) =
  response.bodyString().isNotBlank() && response.header("content-type")?.let {
    StringUtils.deleteWhitespace(it)
      .equals(StringUtils.deleteWhitespace(ContentType.APPLICATION_JSON.toHeaderValue()), ignoreCase = true)
  } ?: false

internal fun readTextFromFile(filePath: String): String = File(filePath).readText()

internal fun List<*>.deepFlattenItems(): List<*> =
  this.asSequence().flatMap { item ->
    (item as Map<String, Any>)["item"]?.let { (it as List<*>).deepFlattenItems() } ?: listOf(item)
  }.toList()
