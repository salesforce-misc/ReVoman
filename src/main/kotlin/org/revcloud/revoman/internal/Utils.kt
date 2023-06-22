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

internal fun List<MutableMap<String, Any>>.deepFlattenItems(parentFolderName: String = ""): List<MutableMap<String, Any>> =
  this.asSequence().flatMap { item ->
    val concatWithParentFolder = if (parentFolderName.isEmpty()) item["name"] as String else "$parentFolderName|>${item["name"]}"
    (item["item"] as? List<MutableMap<String, Any>>)?.deepFlattenItems(concatWithParentFolder) ?: listOf(item.also { it["name"] = "${(item["request"] as Map<String, Any>)["method"]}: $concatWithParentFolder" })
  }.toList()
