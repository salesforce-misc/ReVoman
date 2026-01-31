/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.template

import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.http.JetBrainsHttpTemplateProvider
import com.salesforce.revoman.internal.postman.PostmanTemplateProvider

internal object TemplateProviders {
  fun defaults(): List<TemplateProvider> =
    listOf(
      PostmanTemplateProvider(),
      JetBrainsHttpTemplateProvider(),
    )

  fun loadTemplates(kick: Kick): List<TemplateLoadResult> {
    val sources = loadSources(kick)
    val providers = kick.templateProviders().ifEmpty { defaults() }
    return loadTemplates(sources, providers)
  }

  private fun loadTemplates(
    sources: List<TemplateSource>,
    providers: List<TemplateProvider>,
  ): List<TemplateLoadResult> =
    sources.map { source ->
      val provider =
        providers.firstOrNull { it.supports(source) }
          ?: throw IllegalArgumentException("Unsupported template format: ${source.name}")
      provider.load(source)
    }

  private fun loadSources(kick: Kick): List<TemplateSource> =
    kick.templatePaths().map { TemplateSource.fromPath(it) } +
      kick.templateInputStreams().mapIndexed { index, inputStream ->
        TemplateSource.fromInputStream(index, inputStream)
      }
}
