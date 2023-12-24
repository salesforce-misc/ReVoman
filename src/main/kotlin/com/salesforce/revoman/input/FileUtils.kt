@file:JvmName("FileUtils")

package com.salesforce.revoman.input

import java.io.File
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.Source
import okio.buffer
import okio.source

private fun readFileInResources(fileRelativePath: String): Source =
  FileSystem.RESOURCES.source(fileRelativePath.toPath())

fun bufferFileInResources(fileRelativePath: String): BufferedSource =
  readFileInResources(fileRelativePath).buffer()

fun readFileInResourcesToString(fileRelativePath: String): String =
  bufferFileInResources(fileRelativePath).readUtf8()

fun bufferFile(file: File): BufferedSource = file.source().buffer()

fun readFileToString(file: File): String = bufferFile(file).readUtf8()
