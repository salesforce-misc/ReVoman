/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
@file:JvmName("ClasspathResolver")

package com.salesforce.revoman.input

import java.io.File
import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.FileSystem as NioFileSystem
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystems
import java.util.concurrent.ConcurrentHashMap
import okio.FileSystem
import okio.FileSystem.Companion.SYSTEM
import okio.FileSystem.Companion.asOkioFileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * Resolves a classpath path to a single file. Use [resolveClasspathDir] for directories.
 *
 * Why not `okio.FileSystem.RESOURCES`: that singleton is statically bound to the okio class's
 * classloader and cannot see resources on child / sibling classloaders (bazel runfiles,
 * URLClassLoader, OSGi). This resolver asks the `Thread.currentThread().contextClassLoader`
 * instead, then opens jar entries via NIO ZipFS wrapped as okio FileSystem.
 *
 * Returns:
 * - absolute `path` -> (path, SYSTEM)
 * - classpath path resolving to `file:` URL -> (absolute path on disk, SYSTEM)
 * - classpath path resolving to `jar:file:!/entry` URL -> (`/entry`, NIO-zipfs-as-okio)
 * - unresolvable path -> null
 */
fun resolveClasspath(path: String): Pair<Path, FileSystem>? {
  val p = path.toPath()
  if (p.isAbsolute) return p to SYSTEM
  val url = contextClassLoader().getResource(path) ?: return null
  return urlToOkio(url, fallbackEntry = null)
}

/**
 * Resolves a classpath directory.
 *
 * First tries direct `getResource(dirPath)` -- works for file-system classpath roots. When the
 * direct lookup returns null (typical for jar entries: `URLClassLoader.getResource("some/dir")`
 * returns null even when the jar contains explicit dir entries), falls back to probing for
 * [sentinelRelPath] inside the directory and re-deriving the directory location from the sentinel's
 * URL.
 *
 * Returns the directory's `(Path, FileSystem)` pair, or null if neither the direct lookup nor the
 * sentinel probe finds anything.
 */
fun resolveClasspathDir(dirPath: String, sentinelRelPath: String): Pair<Path, FileSystem>? {
  val p = dirPath.toPath()
  if (p.isAbsolute) return p to SYSTEM
  val direct = contextClassLoader().getResource(dirPath)
  if (direct != null) {
    val resolved = urlToOkio(direct, fallbackEntry = null)
    if (resolved != null) return resolved
  }
  val sentinelClasspath = "${dirPath.trimEnd('/')}/$sentinelRelPath"
  val url = contextClassLoader().getResource(sentinelClasspath) ?: return null
  return urlToOkio(url, fallbackEntry = sentinelClasspath)?.let { (sentinelOkioPath, fs) ->
    val sentinelSegments = sentinelRelPath.trim('/').split('/').count { it.isNotEmpty() }
    var dir: Path = sentinelOkioPath
    repeat(sentinelSegments) { dir = dir.parent ?: return@let null }
    dir to fs
  }
}

private fun contextClassLoader(): ClassLoader =
  Thread.currentThread().contextClassLoader ?: ClasspathResolverInternal::class.java.classLoader

private fun urlToOkio(url: URL, fallbackEntry: String?): Pair<Path, FileSystem>? =
  when (url.protocol) {
    "file" -> File(url.toURI()).toOkioPath() to SYSTEM
    "jar" -> {
      val (jarUri, entry) = splitJarUrl(url, fallbackEntry) ?: return null
      val nioFs = openOrGetJarFileSystem(jarUri)
      val entryPath = nioFs.getPath(entry).toOkioPath()
      entryPath to nioFs.asOkioFileSystem()
    }
    else -> null
  }

private object ClasspathResolverInternal

private val jarFileSystems: ConcurrentHashMap<URI, NioFileSystem> = ConcurrentHashMap()

private fun openOrGetJarFileSystem(jarUri: URI): NioFileSystem =
  jarFileSystems.computeIfAbsent(jarUri) {
    try {
      FileSystems.newFileSystem(it, emptyMap<String, Any>())
    } catch (_: FileSystemAlreadyExistsException) {
      FileSystems.getFileSystem(it)
    }
  }

/**
 * Splits a `jar:file:/path/to.jar!/entry/inside.yaml` URL into the `jar:file:/path/to.jar!/` URI
 * and the decoded `/entry/inside.yaml` entry. URL-encoded segments (e.g. `%20` for space) in the
 * entry portion are decoded so the entry can be passed to `java.nio.file.FileSystem.getPath`, which
 * does not perform URL-decoding. Falls back to `JarURLConnection` for non-trivial URLs.
 */
private fun splitJarUrl(url: URL, fallbackEntry: String?): Pair<URI, String>? {
  val s = url.toString()
  val sep = s.indexOf("!/")
  if (sep > 0) {
    val jarUri = URI.create(s.substring(0, sep + 2))
    // URI parses + decodes percent-encoded segments (e.g. `%20` -> ` `). Required because
    // `java.nio.file.FileSystem.getPath` does not URL-decode and a literal `%20` in an entry
    // name will not match the underlying zip entry whose name is a real space.
    val entry = URI.create(s.substring(sep + 1)).path
    return jarUri to entry
  }
  return runCatching {
      val conn = url.openConnection() as JarURLConnection
      val jarUri = URI.create("jar:" + conn.jarFileURL.toString() + "!/")
      val entry = "/" + (conn.entryName ?: fallbackEntry?.trimStart('/') ?: return@runCatching null)
      jarUri to entry
    }
    .getOrNull()
}
