/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.source
import org.junit.jupiter.api.Test

class V3LoaderJarTest {
  @Test
  fun testLoadV3CollectionFromJarEntries() {
    val jar = packageFixtureIntoJar("src/test/resources/pm-templates/v3/flat", prefix = "flat")
    val jarUri = URI.create("jar:${jar.toURI()}")

    FileSystems.newFileSystem(jarUri, emptyMap<String, Any>()).use { nioFs ->
      val items = V3Loader.load("/flat".toPath(), NioZipFileSystem(nioFs))

      assertThat(items).hasSize(3)
      assertThat(items.map { it.name }).containsExactly("b", "c", "a").inOrder()
    }
  }

  @Test
  fun testSubfolderInheritsGrandparentAuthFromJarEntries() {
    val jar =
      packageFixtureIntoJar("src/test/resources/pm-templates/v3/grandparent", prefix = "grandparent")
    val jarUri = URI.create("jar:${jar.toURI()}")

    FileSystems.newFileSystem(jarUri, emptyMap<String, Any>()).use { nioFs ->
      // Root at the child subfolder inside the jar; it has no auth and must inherit GRANDPARENT by
      // walking up the jar entries (jar-safe ancestor resolution, the v3-jar gap regression guard).
      val items = V3Loader.load("/grandparent/child".toPath(), NioZipFileSystem(nioFs))
      assertThat(items).hasSize(2)
      assertThat(items[0].name).isEqualTo("req")
      assertThat(items[0].request.auth!!.bearer.single().value).isEqualTo("GRANDPARENT")
      assertThat(items[1].name).isEqualTo("grandchild")
    }
  }

  private fun packageFixtureIntoJar(srcDir: String, prefix: String): File {
    val src = File(srcDir)
    require(src.isDirectory) { "fixture source dir not found: $srcDir" }
    val jar = Files.createTempFile("v3-fixture-", ".jar").toFile().apply { deleteOnExit() }
    JarOutputStream(jar.outputStream()).use { jos -> writeDirInto(jos, src, prefix = prefix) }
    return jar
  }

  private fun writeDirInto(jos: JarOutputStream, dir: File, prefix: String) {
    dir.listFiles().orEmpty().forEach { child ->
      val entryName = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
      if (child.isDirectory) {
        jos.putNextEntry(JarEntry("$entryName/"))
        jos.closeEntry()
        writeDirInto(jos, child, prefix = entryName)
      } else {
        jos.putNextEntry(JarEntry(entryName))
        child.inputStream().use { it.copyTo(jos) }
        jos.closeEntry()
      }
    }
  }

  /**
   * Read-only okio FileSystem view over a Java NIO FileSystem (e.g., a jar zipfs). Only the methods
   * V3Loader actually calls are implemented; everything else throws.
   */
  private class NioZipFileSystem(private val nioFs: java.nio.file.FileSystem) : FileSystem() {
    override fun list(dir: Path): List<Path> =
      Files.list(nioFs.getPath(dir.toString())).use { stream ->
        stream.map { it.toOkioPath() }.toList()
      }

    override fun listOrNull(dir: Path): List<Path>? = runCatching { list(dir) }.getOrNull()

    override fun metadataOrNull(path: Path): FileMetadata? =
      runCatching {
          val attrs =
            Files.readAttributes(nioFs.getPath(path.toString()), BasicFileAttributes::class.java)
          FileMetadata(
            isRegularFile = attrs.isRegularFile,
            isDirectory = attrs.isDirectory,
            size = if (attrs.isRegularFile) attrs.size() else null,
          )
        }
        .getOrNull()

    override fun source(file: Path): Source =
      Files.newInputStream(nioFs.getPath(file.toString())).source()

    override fun canonicalize(path: Path): Path = path

    override fun appendingSink(file: Path, mustExist: Boolean): Sink =
      throw UnsupportedOperationException("read-only")

    override fun atomicMove(source: Path, target: Path): Unit =
      throw UnsupportedOperationException("read-only")

    override fun createDirectory(dir: Path, mustCreate: Boolean): Unit =
      throw UnsupportedOperationException("read-only")

    override fun createSymlink(source: Path, target: Path): Unit =
      throw UnsupportedOperationException("read-only")

    override fun delete(path: Path, mustExist: Boolean): Unit =
      throw UnsupportedOperationException("read-only")

    override fun openReadOnly(file: Path): okio.FileHandle =
      throw UnsupportedOperationException("use source()")

    override fun openReadWrite(
      file: Path,
      mustCreate: Boolean,
      mustExist: Boolean,
    ): okio.FileHandle = throw UnsupportedOperationException("read-only")

    override fun sink(file: Path, mustCreate: Boolean): Sink =
      throw UnsupportedOperationException("read-only")
  }
}
