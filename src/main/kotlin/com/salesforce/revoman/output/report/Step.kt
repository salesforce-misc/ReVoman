/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.endsWith
import com.salesforce.revoman.output.report.Folder.Companion.FOLDER_DELIMITER
import com.squareup.moshi.JsonClass
import java.util.Collections.indexOfSubList

@JsonClass(generateAdapter = true)
data class Step(
  @JvmField val index: String,
  @JvmField val rawPmStep: Item,
  @JvmField val parentFolder: Folder? = null,
) {
  @JvmField val name: String = rawPmStep.name
  @JvmField var preStepHookCount: Int = 0
  @JvmField var postStepHookCount: Int = 0
  @JvmField
  val displayPath =
    parentFolder?.let { "$it$STEP_NAME_SEPARATOR$name$STEP_NAME_TERMINATOR" } ?: name
  @JvmField
  val displayName =
    "$index$INDEX_SEPARATOR${rawPmStep.httpMethod}$HTTP_METHOD_SEPARATOR$displayPath"

  @JvmField val isInRoot: Boolean = parentFolder == null

  fun isInFolder(folderPath: String): Boolean =
    parentFolder?.let {
      indexOfSubList(
        it.path.map { f -> f.name },
        folderPath.trim(*FOLDER_DELIMITER.toCharArray()).split(FOLDER_DELIMITER),
      ) != -1
    } ?: folderPath.isBlank()

  fun stepNameMatches(stepName: String): Boolean =
    name == stepName || displayName == stepName || pathEndsWith(stepName)

  private fun pathEndsWith(semiStepPath: String): Boolean {
    val stepNameFromPath: List<String> = semiStepPath.split(STEP_NAME_SEPARATOR)
    if (stepNameFromPath.size != 2 || name != stepNameFromPath[1]) {
      return false
    }
    val folderPath = stepNameFromPath[0].split(FOLDER_DELIMITER)
    return parentFolder?.path?.endsWith(folderPath) ?: folderPath.isEmpty()
  }

  override fun toString(): String = displayName

  companion object {
    const val HTTP_METHOD_SEPARATOR = " ~~> "
    const val INDEX_SEPARATOR = " ### "
    const val STEP_NAME_SEPARATOR = "<|||"
    const val STEP_NAME_TERMINATOR = "|||>"
  }
}

@JsonClass(generateAdapter = true)
data class Folder
@JvmOverloads
constructor(
  @JvmField val name: String,
  @JvmField val parent: Folder? = null,
  @JvmField val subFolders: MutableList<Folder> = mutableListOf(),
) {
  @JvmField val isRoot: Boolean = parent == null
  @JvmField val parentPath: List<Folder> = parent?.parentPath?.plus(parent) ?: emptyList()
  @JvmField val path: List<Folder> = parentPath + this

  override fun toString(): String =
    when {
      isRoot -> name
      else ->
        parentPath.joinToString(separator = FOLDER_DELIMITER, postfix = FOLDER_DELIMITER) {
          it.name
        } + name
    }

  companion object {
    const val FOLDER_DELIMITER = "|>"
  }
}
