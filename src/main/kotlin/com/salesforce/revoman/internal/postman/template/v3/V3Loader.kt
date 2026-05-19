/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.salesforce.revoman.internal.postman.template.Auth
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

internal object V3Loader {
  private const val DEF_REL_PATH = ".resources/definition.yaml"
  private const val REQUEST_SUFFIX = ".request.yaml"

  fun load(rootDir: File): List<Item> {
    require(rootDir.isDirectory) {
      "v3 collection root must be a directory: ${rootDir.absolutePath}"
    }
    val rootDef = readDefOrThrow(rootDir)
    return walk(rootDir, parentAuth = V3ToV2Converter.toAuth(rootDef.auth))
  }

  private fun walk(dir: File, parentAuth: Auth?): List<Item> {
    val def = readDefOrNull(dir)
    val effectiveAuth =
      if (def != null && def.auth.isNotEmpty()) V3ToV2Converter.toAuth(def.auth) else parentAuth

    val requestEntries: List<Pair<Item, Int>> =
      (dir.listFiles { f -> f.isFile && f.name.endsWith(REQUEST_SUFFIX) } ?: emptyArray()).map {
        file ->
        val v3req = V3YamlReader.readRequest(file.readText())
        val fallbackName = file.name.removeSuffix(REQUEST_SUFFIX)
        val item =
          V3ToV2Converter.toItem(v3req, fallbackName = fallbackName, inheritedAuth = effectiveAuth)
        item to (v3req.order ?: Int.MAX_VALUE)
      }

    val folderEntries: List<Pair<Item, Int>> =
      (dir.listFiles { f -> f.isDirectory && hasDef(f) } ?: emptyArray()).map { sub ->
        val subDef = readDefOrThrow(sub)
        val children = walk(sub, parentAuth = effectiveAuth)
        val folderItem = Item(name = sub.name, item = children, request = Request())
        folderItem to (subDef.order ?: Int.MAX_VALUE)
      }

    return (folderEntries + requestEntries).sortedBy { it.second }.map { it.first }
  }

  private fun hasDef(dir: File): Boolean = File(dir, DEF_REL_PATH).isFile

  private fun readDefOrNull(dir: File): V3CollectionDef? {
    val defFile = File(dir, DEF_REL_PATH)
    if (!defFile.isFile) return null
    return V3YamlReader.readCollectionDef(defFile.readText())
  }

  private fun readDefOrThrow(dir: File): V3CollectionDef =
    readDefOrNull(dir)
      ?: error("Not a v3 collection root: ${dir.absolutePath}. Missing $DEF_REL_PATH")
}

private val logger = KotlinLogging.logger {}
