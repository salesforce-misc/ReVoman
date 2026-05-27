/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
@file:JvmName("FileUtils")

package com.salesforce.revoman.input

import java.io.File
import java.io.InputStream
import okio.BufferedSource
import okio.FileSystem.Companion.RESOURCES
import okio.FileSystem.Companion.SYSTEM
import okio.Path.Companion.toPath
import okio.buffer
import okio.source

fun bufferFile(filePath: String): BufferedSource =
  filePath.toPath().let { (if (it.isAbsolute) SYSTEM else RESOURCES).source(it).buffer() }

fun readFileToString(filePath: String): String = bufferFile(filePath).readUtf8()

fun bufferInputStream(inputStream: InputStream): BufferedSource = inputStream.source().buffer()

fun readInputStreamToString(inputStream: InputStream): String =
  bufferInputStream(inputStream).readUtf8()

fun bufferFile(file: File): BufferedSource = file.source().buffer()

fun readFileToString(file: File): String = bufferFile(file).readUtf8()

fun isV3EnvFile(path: String): Boolean = path.endsWith(".yaml") || path.endsWith(".yml")

fun writeToFile(filePath: String, content: String) =
  SYSTEM.write(filePath.toPath()) { writeUtf8(content) }

private const val V3_DEFINITION_REL_PATH = ".resources/definition.yaml"

fun isV3Collection(path: String): Boolean =
  runCatching {
      val p = path.toPath()
      val fs = if (p.isAbsolute) SYSTEM else RESOURCES
      val md = fs.metadataOrNull(p) ?: return@runCatching false
      if (!md.isDirectory) return@runCatching false
      fs.exists(p / V3_DEFINITION_REL_PATH)
    }
    .getOrDefault(false)

fun bufferV3Definition(collectionDir: String): BufferedSource {
  val p = collectionDir.toPath()
  val fs = if (p.isAbsolute) SYSTEM else RESOURCES
  return fs.source(p / V3_DEFINITION_REL_PATH).buffer()
}
