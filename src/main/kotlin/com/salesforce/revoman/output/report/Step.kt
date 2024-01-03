/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: 
 *           Apache License Version 2.0 
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/
package com.salesforce.revoman.output.report

import com.salesforce.revoman.internal.postman.state.Request
import com.salesforce.revoman.output.endsWith
import com.salesforce.revoman.output.report.Folder.Companion.FOLDER_DELIMITER
import java.util.Collections.indexOfSubList

data class Step(
  @JvmField val index: String,
  @JvmField val name: String,
  @JvmField val rawRequest: Request,
  @JvmField val parentFolder: Folder? = null
) {
  @JvmField val path = parentFolder?.let { "$it$STEP_SEPARATOR$name" } ?: name
  @JvmField
  val displayName = "$index$INDEX_SEPARATOR${rawRequest.method}$HTTP_METHOD_SEPARATOR$path"

  @JvmField val isInRoot: Boolean = parentFolder == null

  fun isInFolder(folderPath: String): Boolean =
    parentFolder?.let {
      indexOfSubList(
        it.path.map { f -> f.name },
        folderPath.trim(*FOLDER_DELIMITER.toCharArray()).split(FOLDER_DELIMITER)
      ) != -1
    } ?: folderPath.isBlank()

  fun stepNameMatches(stepName: String): Boolean =
    name == stepName || displayName == stepName || pathEndsWith(stepName)

  private fun pathEndsWith(semiStepPath: String): Boolean {
    val stepNameFromPath: List<String> = semiStepPath.split(STEP_SEPARATOR)
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
    const val STEP_SEPARATOR = "|="
  }
}

data class Folder
@JvmOverloads
constructor(
  @JvmField val name: String,
  @JvmField val parent: Folder? = null,
  @JvmField val subFolders: MutableList<Folder> = mutableListOf()
) {
  @JvmField val isRoot: Boolean = parent == null
  val path: List<Folder>
    @JvmName("path") get() = parentPath + this

  val parentPath: List<Folder>
    @JvmName("parentPath") get() = parent?.parentPath?.plus(parent) ?: emptyList()

  override fun toString(): String =
    if (isRoot) name
    else
      parentPath.joinToString(separator = FOLDER_DELIMITER, postfix = FOLDER_DELIMITER) {
        it.name
      } + name

  companion object {
    const val FOLDER_DELIMITER = "|>"
  }
}
