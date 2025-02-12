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
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.source

fun bufferFileInResources(fileRelativePath: String): BufferedSource =
  FileSystem.RESOURCES.source(fileRelativePath.toPath()).buffer()

fun readFileInResourcesToString(fileRelativePath: String): String =
  bufferFileInResources(fileRelativePath).readUtf8()

fun bufferInputStream(inputStream: InputStream): BufferedSource = inputStream.source().buffer()

fun readInputStreamToString(inputStream: InputStream): String =
  bufferInputStream(inputStream).readUtf8()

fun bufferFile(file: File): BufferedSource = file.source().buffer()

fun readFileToString(file: File): String = bufferFile(file).readUtf8()

fun writeToFileInTheModule(fileRelativePath: String, content: String) =
  FileSystem.SYSTEM.write(fileRelativePath.toPath()) { writeUtf8(content) }
