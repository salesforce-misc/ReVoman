/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.template

import com.salesforce.revoman.input.readFileToString
import com.salesforce.revoman.input.readInputStreamToString
import com.salesforce.revoman.output.report.Step
import java.io.File
import java.io.InputStream

enum class TemplateType {
  POSTMAN,
  JETBRAINS_HTTP,
}

internal data class TemplateSource(
  val name: String,
  val content: String,
  val basePath: String? = null,
  val extension: String? = null,
) {
  fun resolveRelativePath(path: String): String =
    when {
      path.isBlank() -> path
      File(path).isAbsolute -> path
      basePath.isNullOrBlank() -> path
      else -> "${basePath.trimEnd('/')}/${path.trimStart('/')}"
    }

  companion object {
    fun fromPath(path: String): TemplateSource {
      val file = File(path)
      val basePath = file.parent
      val extension = file.extension.takeIf { it.isNotBlank() }?.lowercase()
      return TemplateSource(
        name = path,
        content = readFileToString(path),
        basePath = basePath,
        extension = extension,
      )
    }

    fun fromInputStream(index: Int, inputStream: InputStream): TemplateSource =
      TemplateSource(
        name = "input-stream-$index",
        content = readInputStreamToString(inputStream),
      )
  }
}

internal data class TemplateLoadResult(
  val templateType: TemplateType,
  val sourceName: String,
  val steps: List<Step>,
  val fileVariables: Map<String, Any?> = emptyMap(),
)

internal interface TemplateProvider {
  fun supports(source: TemplateSource): Boolean

  fun load(source: TemplateSource): TemplateLoadResult
}
