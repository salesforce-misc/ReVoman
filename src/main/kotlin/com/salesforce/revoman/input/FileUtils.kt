/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
@file:JvmName("FileUtils")

package com.salesforce.revoman.input

import com.salesforce.revoman.internal.postman.template.v3.V3YamlReader
import com.salesforce.revoman.internal.postman.template.v3.V3YamlWriter
import com.salesforce.revoman.internal.postman.template.v3.V3_DEFINITION_REL_PATH
import com.salesforce.revoman.output.ledger.LedgerFile
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import okio.BufferedSource
import okio.FileSystem.Companion.SYSTEM
import okio.Path.Companion.toPath
import okio.buffer
import okio.source

fun bufferFile(filePath: String): BufferedSource {
  val (path, fs) =
    resolveClasspath(filePath)
      ?: throw FileNotFoundException("file not found on classpath: $filePath")
  return fs.source(path).buffer()
}

fun readFileToString(filePath: String): String = bufferFile(filePath).readUtf8()

fun bufferInputStream(inputStream: InputStream): BufferedSource = inputStream.source().buffer()

fun readInputStreamToString(inputStream: InputStream): String =
  bufferInputStream(inputStream).readUtf8()

fun bufferFile(file: File): BufferedSource = file.source().buffer()

fun readFileToString(file: File): String = bufferFile(file).readUtf8()

/** Returns `true` if `path` ends with `.yaml` or `.yml` (case-sensitive). */
fun isV3EnvFile(path: String): Boolean = path.endsWith(".yaml") || path.endsWith(".yml")

fun writeToFile(filePath: String, content: String) =
  SYSTEM.write(filePath.toPath()) { writeUtf8(content) }

/**
 * Returns `true` if `path` is a v3 collection directory (contains `.resources/definition.yaml`).
 *
 * Total: never throws. Returns `false` for missing paths, files, or any I/O error. Resolves
 * absolute paths via the filesystem and relative paths via the thread context classloader
 * (jar-aware via NIO ZipFS).
 */
fun isV3Collection(path: String): Boolean =
  runCatching {
      val (p, fs) = resolveClasspathDir(path, V3_DEFINITION_REL_PATH) ?: return@runCatching false
      val md = fs.metadataOrNull(p) ?: return@runCatching false
      if (!md.isDirectory) return@runCatching false
      fs.exists(p / V3_DEFINITION_REL_PATH)
    }
    .getOrDefault(false)

/**
 * Buffers the root `.resources/definition.yaml` of a v3 collection directory.
 *
 * Throws `FileNotFoundException` if the marker is missing. Gate with [isV3Collection] to avoid.
 */
fun bufferV3Definition(collectionDir: String): BufferedSource {
  val (p, fs) =
    resolveClasspathDir(collectionDir, V3_DEFINITION_REL_PATH)
      ?: throw FileNotFoundException("v3 collection not found on classpath: $collectionDir")
  return fs.source(p / V3_DEFINITION_REL_PATH).buffer()
}

/** Read a ledger file (postman-env `values` + `x-revoman-ledger` sibling). */
fun readLedgerYaml(filePath: String): LedgerFile =
  V3YamlReader.readLedger(readFileToString(filePath))

/**
 * Write a ledger file. `values` stays postman-importable; metadata in the `x-revoman-ledger`
 * sibling.
 */
fun writeLedgerYaml(filePath: String, ledger: LedgerFile) =
  writeToFile(filePath, V3YamlWriter.dump(ledger))
