/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.salesforce.revoman.input.resolveClasspathDir
import com.salesforce.revoman.internal.postman.template.Auth
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.Request
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.FileNotFoundException
import okio.FileSystem
import okio.Path
import okio.buffer

internal const val V3_DEFINITION_REL_PATH = ".resources/definition.yaml"

internal object V3Loader {
  private const val REQUEST_SUFFIX = ".request.yaml"

  fun load(rootPath: String): List<Item> {
    val (path, fs) =
      resolveClasspathDir(rootPath, V3_DEFINITION_REL_PATH)
        ?: throw FileNotFoundException("v3 collection not found on classpath: $rootPath")
    return load(path, fs)
  }

  fun load(rootPath: Path, fs: FileSystem): List<Item> {
    require(fs.metadataOrNull(rootPath)?.isDirectory == true) {
      "v3 collection root must be a directory: $rootPath"
    }
    val rootDef = readDefOrThrow(rootPath, fs)
    val rootAuth = V3ToV2Converter.toAuth(rootDef.auth)
    // If the root folder itself declares auth, it wins. Otherwise seed from the nearest ancestor
    // that has a definition.yaml with auth (Postman collection->folder inheritance across the
    // filesystem). Walk UP within the same FileSystem; do NOT re-resolve via classpath (jar dirs
    // are not resolvable by getResource).
    val seedAuth = rootAuth ?: nearestAncestorAuth(rootPath, fs)
    return walk(rootPath, fs, parentAuth = seedAuth)
  }

  private fun nearestAncestorAuth(start: Path, fs: FileSystem): Auth? {
    var dir = start.parent
    while (dir != null) {
      val def = readDefOrNull(dir, fs) ?: break // stop at first ancestor with no definition.yaml
      // An explicitly-declared (non-empty) auth list bounds inheritance, mirroring walk()'s
      // `def.auth.isNotEmpty()` check — even if the declared type is unsupported and toAuth
      // returns null, the nearest declaration wins (no skipping further up).
      if (def.auth.isNotEmpty()) return V3ToV2Converter.toAuth(def.auth)
      dir = dir.parent
    }
    return null
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
          val rawYaml = fs.source(file).buffer().readUtf8()
          val v3req = V3YamlReader.readRequest(rawYaml)
          val fallbackName = file.name.removeSuffix(REQUEST_SUFFIX)
          val item =
            V3ToV2Converter.toItem(
                v3req,
                fallbackName = fallbackName,
                inheritedAuth = effectiveAuth,
              )
              .copy(sourceHash = sha256Hex(rawYaml))
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

internal fun sha256Hex(s: String): String =
  java.security.MessageDigest.getInstance("SHA-256")
    .digest(s.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private val logger = KotlinLogging.logger {}
