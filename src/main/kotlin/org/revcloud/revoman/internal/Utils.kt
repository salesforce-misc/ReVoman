package org.revcloud.revoman.internal

import java.io.File
import java.util.function.Consumer
import mu.KotlinLogging
import org.apache.commons.lang3.StringUtils
import org.http4k.core.ContentType
import org.http4k.core.Response
import org.revcloud.revoman.input.HookType
import org.revcloud.revoman.internal.postman.state.Item
import org.revcloud.revoman.output.FOLDER_DELIMITER
import org.revcloud.revoman.output.Rundown

private val logger = KotlinLogging.logger {}

internal fun isContentTypeApplicationJson(response: Response) =
  response.bodyString().isNotBlank() &&
    response.header("content-type")?.let {
      StringUtils.deleteWhitespace(it)
        .equals(
          StringUtils.deleteWhitespace(ContentType.APPLICATION_JSON.toHeaderValue()),
          ignoreCase = true
        )
    }
      ?: false

internal fun readTextFromFile(filePath: String): String = File(filePath).readText()

internal fun List<Item>.deepFlattenItems(parentFolderName: String = ""): List<Item> =
  asSequence()
    .flatMap { item ->
      val concatWithParentFolder =
        if (parentFolderName.isEmpty()) item.name else "$parentFolderName|>${item.name}"
      item.item?.deepFlattenItems(concatWithParentFolder)
        ?: listOf(item.copy(name = "${item.request.method}: $concatWithParentFolder"))
    }
    .toList()

internal fun getHookForStep(
  hooks: Map<Pair<String, HookType>, Consumer<Rundown>>,
  stepName: String,
  hookType: HookType
): Consumer<Rundown>? =
  (hooks[stepName to hookType] ?: hooks[stepName.substringAfterLast(FOLDER_DELIMITER) to hookType])
    ?.also { logger.info { "Found a $hookType hook for $stepName" } }
