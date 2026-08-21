/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import performance.hash.Sha256
import performance.json.CanonicalJson
import tools.jackson.databind.node.JsonNodeFactory

/** Generates the canonical, treatment-free source and tool closure used by protocol identity. */
@CacheableTask
abstract class GenerateProtocolManifestTask : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val captureRunnerSourceDirectory: DirectoryProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val protocolSources: ConfigurableFileCollection

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val benchmarkJar: RegularFileProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val runnerDistributionDirectory: DirectoryProperty

  @get:Classpath abstract val benchmarkDependencies: ConfigurableFileCollection

  @get:org.gradle.api.tasks.Input abstract val toolIdentities: MapProperty<String, String>

  @get:OutputFile abstract val manifestFile: RegularFileProperty

  @TaskAction
  fun generate() {
    val captureRoot = captureRunnerSourceDirectory.get().asFile.toPath().toAbsolutePath().normalize()
    val entries = linkedMapOf<String, Sha256>()

    protocolSources.files.sortedBy { it.absolutePath }.forEach { source ->
      val path = source.toPath().toAbsolutePath().normalize()
      require(path.startsWith(captureRoot)) { "protocol source must be inside capture runner tree" }
      when {
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ->
          regularFiles(path).forEach { file ->
            addEntry(entries, "source/${portable(captureRoot.relativize(file))}", file)
          }
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ->
          addEntry(entries, "source/${portable(captureRoot.relativize(path))}", path)
        else -> error("protocol source must be a regular file or directory")
      }
    }

    addEntry(entries, "compiled/benchmark/revoman-jmh.jar", benchmarkJar.get().asFile.toPath())
    val runnerRoot = runnerDistributionDirectory.get().asFile.toPath().toAbsolutePath().normalize()
    regularFiles(runnerRoot).forEach { file ->
      addEntry(entries, "compiled/runner/${portable(runnerRoot.relativize(file))}", file)
    }
    benchmarkDependencies.files.sortedBy { it.name }.forEach { dependency ->
      addEntry(entries, "dependencies/${dependency.name}", dependency.toPath())
    }

    val sourceClosure =
      JsonNodeFactory.instance.arrayNode().apply {
        entries.toSortedMap().forEach { (path, sha256) ->
          add(
            JsonNodeFactory.instance.objectNode().apply {
              put("path", path)
              put("sha256", sha256.hex)
            },
          )
        }
      }
    val identities =
      JsonNodeFactory.instance.objectNode().apply {
        toolIdentities.get().toSortedMap().forEach { (key, value) -> put(key, value) }
      }
    val document =
      JsonNodeFactory.instance.objectNode().apply {
        set("sourceClosure", sourceClosure)
        set("toolIdentities", identities)
      }
    val output = manifestFile.get().asFile.toPath()
    Files.createDirectories(output.parent)
    Files.write(output, CanonicalJson.encode(document))
  }

  private fun addEntry(entries: MutableMap<String, Sha256>, logicalPath: String, file: Path) {
    require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) {
      "protocol closure accepts only regular files"
    }
    check(entries.put(logicalPath, Sha256.digest(Files.readAllBytes(file))) == null) {
      "duplicate protocol closure path"
    }
  }

  private fun regularFiles(root: Path): List<Path> {
    require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
      "protocol closure root must be a directory"
    }
    return Files.walk(root).use { paths ->
      val all = paths.toList()
      require(all.none(Files::isSymbolicLink)) { "protocol closure does not allow symbolic links" }
      all
        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        .sortedBy { portable(root.relativize(it)) }
    }
  }
}

internal fun portable(path: Path): String = path.joinToString(separator = "/") { it.toString() }
