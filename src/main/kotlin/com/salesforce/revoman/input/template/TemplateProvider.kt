/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.template

import com.salesforce.revoman.input.readFileToString
import com.salesforce.revoman.input.readInputStreamToString
import com.salesforce.revoman.output.report.Step
import java.io.File
import java.io.InputStream

internal enum class TemplateFormat {
  POSTMAN_JSON,
  JETBRAINS_HTTP,
}

internal data class TemplateSource(
  val content: String,
  val sourcePath: String?,
  val sourceName: String,
) {
  private val sourceFile: File? = sourcePath?.let { File(it) }
  private val isAbsoluteSourcePath = sourceFile?.isAbsolute == true
  private val baseDir: String? = sourceFile?.parent

  val extension: String? = sourceFile?.extension?.ifBlank { null }

  fun resolveRelativePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return trimmed
    if (File(trimmed).isAbsolute) return trimmed
    return baseDir?.let { base ->
      if (isAbsoluteSourcePath) File(base, trimmed).path else File(base, trimmed).path.replace('\\', '/')
    } ?: trimmed
  }

  companion object {
    fun fromPath(path: String): TemplateSource =
      TemplateSource(readFileToString(path), path, path)

    fun fromInputStream(inputStream: InputStream, sourceName: String): TemplateSource =
      TemplateSource(readInputStreamToString(inputStream), null, sourceName)
  }
}

internal data class TemplateParseResult(
  val format: TemplateFormat,
  val sourceName: String,
  val steps: List<Step>,
  val fileVariables: Map<String, Any?> = emptyMap(),
)

internal interface TemplateProvider {
  val format: TemplateFormat

  fun supports(source: TemplateSource): Boolean

  fun parse(source: TemplateSource): TemplateParseResult
}

internal object TemplateProviders {
  private val providers: List<TemplateProvider> =
    listOf(PostmanTemplateProvider(), JetbrainsHttpTemplateProvider())

  fun defaults(): List<TemplateProvider> = providers

  fun providerFor(source: TemplateSource): TemplateProvider =
    providers.firstOrNull { it.supports(source) }
      ?: error("No TemplateProvider found for '${source.sourceName}'")
}
