package org.revcloud.revoman.internal

import mu.KotlinLogging
import org.apache.commons.lang3.StringUtils
import org.http4k.core.ContentType
import org.http4k.core.Response
import org.revcloud.revoman.input.HookType
import org.revcloud.revoman.output.Rundown
import java.io.File
import java.util.function.Consumer

private val logger = KotlinLogging.logger {}

internal fun isContentTypeApplicationJson(response: Response) =
  response.bodyString().isNotBlank() && response.header("content-type")?.let {
    StringUtils.deleteWhitespace(it)
      .equals(StringUtils.deleteWhitespace(ContentType.APPLICATION_JSON.toHeaderValue()), ignoreCase = true)
  } ?: false

internal fun readTextFromFile(filePath: String): String = File(filePath).readText()

internal fun List<MutableMap<String, Any>>.deepFlattenItems(parentFolderName: String = ""): List<Map<String, Any>> =
  this.asSequence().flatMap { item ->
    val concatWithParentFolder = if (parentFolderName.isEmpty()) item["name"] as String else "$parentFolderName|>${item["name"]}"
    (item["item"] as? List<MutableMap<String, Any>>)?.deepFlattenItems(concatWithParentFolder) ?: listOf(item.also { it["name"] = "${(item["request"] as Map<String, Any>)["method"]}: $concatWithParentFolder" })
  }.toList()

internal fun getHookForStep(
  hooks: Map<Pair<String, HookType>, Consumer<Rundown>>,
  stepName: String,
  hookType: HookType
): Consumer<Rundown>? {
  logger.info { "Found a $hookType for $stepName" }
  return (hooks[stepName to hookType] ?: hooks[stepName.substringAfterLast("|>") to hookType])
}

