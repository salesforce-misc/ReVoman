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
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

internal const val V3_DEFINITION_REL_PATH = ".resources/definition.yaml"

internal object V3Loader {
  private const val REQUEST_SUFFIX = ".request.yaml"

  fun load(rootPath: String): List<Item> {
    val (path, fs) = resolvePath(rootPath)
    return load(path, fs)
  }

  fun load(rootPath: Path, fs: FileSystem): List<Item> {
    require(fs.metadataOrNull(rootPath)?.isDirectory == true) {
      "v3 collection root must be a directory: $rootPath"
    }
    val rootDef = readDefOrThrow(rootPath, fs)
    return walk(rootPath, fs, parentAuth = V3ToV2Converter.toAuth(rootDef.auth))
  }

  private fun walk(dir: Path, fs: FileSystem, parentAuth: Auth?): List<Item> {
    val def = readDefOrNull(dir, fs)
    val effectiveAuth =
      if (def != null && def.auth.isNotEmpty()) V3ToV2Converter.toAuth(def.auth) else parentAuth

    val children = fs.list(dir)
    val requestEntries: List<Pair<Item, Int>> =
      children
        .filter { fs.metadataOrNull(it)?.isRegularFile == true && it.name.endsWith(REQUEST_SUFFIX) }
        .map { file ->
          val v3req = V3YamlReader.readRequest(fs.source(file).buffer().readUtf8())
          val fallbackName = file.name.removeSuffix(REQUEST_SUFFIX)
          val item =
            V3ToV2Converter.toItem(
              v3req,
              fallbackName = fallbackName,
              inheritedAuth = effectiveAuth,
            )
          item to (v3req.order ?: Int.MAX_VALUE)
        }

    val folderEntries: List<Pair<Item, Int>> =
      children
        .filter { fs.metadataOrNull(it)?.isDirectory == true && hasDef(it, fs) }
        .map { sub ->
          val subDef = readDefOrThrow(sub, fs)
          val nestedItems = walk(sub, fs, parentAuth = effectiveAuth)
          val folderItem = Item(name = sub.name, item = nestedItems, request = Request())
          folderItem to (subDef.order ?: Int.MAX_VALUE)
        }

    return (folderEntries + requestEntries).sortedBy { it.second }.map { it.first }
  }

  private fun resolvePath(path: String): Pair<Path, FileSystem> {
    val p = path.toPath()
    val fs = if (p.isAbsolute) FileSystem.SYSTEM else FileSystem.RESOURCES
    return p to fs
  }

  private fun hasDef(dir: Path, fs: FileSystem): Boolean = fs.exists(dir / V3_DEFINITION_REL_PATH)

  private fun readDefOrNull(dir: Path, fs: FileSystem): V3CollectionDef? {
    val defFile = dir / V3_DEFINITION_REL_PATH
    if (!fs.exists(defFile)) return null
    return V3YamlReader.readCollectionDef(fs.source(defFile).buffer().readUtf8())
  }

  private fun readDefOrThrow(dir: Path, fs: FileSystem): V3CollectionDef =
    readDefOrNull(dir, fs)
      ?: error("Not a v3 collection root: $dir. Missing $V3_DEFINITION_REL_PATH")
}

private val logger = KotlinLogging.logger {}
