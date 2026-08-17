/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.distribution

import java.io.PrintWriter
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.spi.ToolProvider
import java.util.zip.CRC32

internal data class JarInspection(
  val effectiveClasses: Set<String>,
  val allClasses: Set<String>,
  val containsTestContent: Boolean,
  val serviceProviders: Set<String>,
  val benchmarkNames: Set<String>?,
  val hasCompilerHints: Boolean,
  val problems: List<DistributionProblem>,
)

internal object JarValidator {
  fun inspect(
    path: Path,
    featureVersion: Int,
    projectBuilt: Boolean,
  ): JarInspection =
    runCatching {
        inspectJar(
          path = path,
          featureVersion = featureVersion,
          jdkValidationSucceeded = !projectBuilt || validateWithCurrentJdk(path),
          projectBuilt = projectBuilt,
        )
      }
      .getOrElse {
        JarInspection(
          effectiveClasses = emptySet(),
          allClasses = emptySet(),
          containsTestContent = false,
          serviceProviders = emptySet(),
          benchmarkNames = null,
          hasCompilerHints = false,
          problems = listOf(DistributionProblem.INVALID_JAR),
        )
      }

  private fun inspectJar(
    path: Path,
    featureVersion: Int,
    jdkValidationSucceeded: Boolean,
    projectBuilt: Boolean,
  ): JarInspection =
    JarFile(path.toFile(), true).use { jar ->
      val entries = jar.entries().asSequence().toList()
      val problems = mutableListOf<DistributionProblem>()
      if (!jdkValidationSucceeded) {
        problems += DistributionProblem.INVALID_JAR
      }
      if (entries.size > MAX_JAR_ENTRIES || entries.map(JarEntry::getName).distinct().size != entries.size) {
        problems += DistributionProblem.INVALID_JAR
      }

      val multiRelease =
        jar.manifest?.mainAttributes?.getValue("Multi-Release")?.equals("true", true) == true
      val baseClasses = mutableMapOf<String, String>()
      val versionedClasses = mutableMapOf<String, MutableList<Pair<Int, String>>>()
      val allClasses = mutableSetOf<String>()
      val serviceProviders = mutableSetOf<String>()
      var benchmarkNames: Set<String>? = null
      var hasCompilerHints = false

      entries.forEach { entry ->
        val name = entry.name
        if (!validJarEntryName(name)) {
          problems += DistributionProblem.INVALID_JAR
        }
        if (!entry.isDirectory && !hasValidEntryBytes(jar, entry)) {
          problems += DistributionProblem.INVALID_JAR
        }
        if (!entry.isDirectory && name.endsWith(CLASS_SUFFIX) && !hasClassFileMagic(jar, entry)) {
          problems += DistributionProblem.INVALID_JAR
        }

        when {
          name == BENCHMARK_LIST -> {
            benchmarkNames =
              readBoundedEntry(jar, entry)?.let(::parseBenchmarkList)
                ?: run {
                  problems += DistributionProblem.INVALID_BENCHMARK_METADATA
                  emptySet()
                }
          }
          name == COMPILER_HINTS -> {
            hasCompilerHints = readBoundedEntry(jar, entry)?.isNotEmpty() == true
          }
          name.startsWith(SERVICE_PREFIX) && !entry.isDirectory -> {
            serviceProviders += validateServiceDescriptor(jar, entry, problems)
          }
        }

        when {
          entry.isDirectory -> Unit
          name.startsWith(VERSIONED_PREFIX) -> {
            val versioned = VERSIONED_ENTRY.matchEntire(name)
            if (versioned == null) {
              problems += DistributionProblem.INVALID_MULTI_RELEASE_JAR
            } else {
              val versionText = versioned.groupValues[1]
              val version = versionText.toIntOrNull()
              val effectiveName = versioned.groupValues[2]
              if (
                version == null ||
                  version < MIN_MULTI_RELEASE_VERSION ||
                  (versionText.length > 1 && versionText.startsWith('0')) ||
                  effectiveName.startsWith("META-INF/") ||
                  !multiRelease
              ) {
                problems += DistributionProblem.INVALID_MULTI_RELEASE_JAR
              }
              if (effectiveName.endsWith(CLASS_SUFFIX)) {
                val identity = binaryIdentity(effectiveName)
                allClasses += identity
                if (version != null && version >= MIN_MULTI_RELEASE_VERSION) {
                  versionedClasses.getOrPut(effectiveName, ::mutableListOf) += version to identity
                }
              }
            }
          }
          isLoadableClassEntry(name) -> {
            val identity = binaryIdentity(name)
            allClasses += identity
            baseClasses[name] = identity
          }
        }
      }

      val effectiveClasses =
        (baseClasses.keys + versionedClasses.keys)
          .asSequence()
          .mapNotNull { effectiveName ->
            val versionedIdentity =
              versionedClasses[effectiveName]
                ?.filter { (version) -> multiRelease && version <= featureVersion }
                ?.maxByOrNull(Pair<Int, String>::first)
                ?.second
            versionedIdentity ?: baseClasses[effectiveName]
          }
          .filterNot { identity -> identity == MODULE_INFO }
          .toSet()

      JarInspection(
        effectiveClasses = immutableSet(effectiveClasses),
        allClasses = immutableSet(allClasses),
        containsTestContent = allClasses.any { identity -> isTestClass(identity, projectBuilt) },
        serviceProviders = immutableSet(serviceProviders),
        benchmarkNames = benchmarkNames?.let(::immutableSet),
        hasCompilerHints = hasCompilerHints,
        problems = immutableList(problems.distinct()),
      )
    }

  private fun isTestClass(identity: String, projectBuilt: Boolean): Boolean {
    val simpleName = identity.substringAfterLast('.').substringBefore('$')
    if (identity.contains(".jmh_generated.") && simpleName.endsWith("_jmhTest")) {
      return false
    }
    return identity.startsWith("org.junit.") ||
      identity.startsWith("org.junit.jupiter.") ||
      identity.startsWith("io.kotest.") ||
      identity.startsWith("io.mockk.") ||
      identity.startsWith("net.bytebuddy.") ||
      (projectBuilt &&
        (simpleName.endsWith("Test") ||
          simpleName.endsWith("Tests") ||
          simpleName.endsWith("Spec")))
  }

  private fun validateWithCurrentJdk(path: Path): Boolean {
    val tool = ToolProvider.findFirst(JAR_TOOL_NAME).orElse(null) ?: return false
    return PrintWriter(Writer.nullWriter()).use { output ->
      PrintWriter(Writer.nullWriter()).use { error ->
        tool.run(output, error, "--validate", "--file", path.toString()) == 0
      }
    }
  }

  private fun validateServiceDescriptor(
    jar: JarFile,
    entry: JarEntry,
    problems: MutableList<DistributionProblem>,
  ): Set<String> {
    val serviceName = entry.name.removePrefix(SERVICE_PREFIX)
    val content = readBoundedEntry(jar, entry)?.let(::decodeStrictUtf8)
    if (!isBinaryName(serviceName) || content == null) {
      problems += DistributionProblem.INVALID_SERVICE_DESCRIPTOR
      return emptySet()
    }
    val providers =
      content
        .lineSequence()
        .map { line -> line.substringBefore('#').trim() }
        .filter(String::isNotEmpty)
        .toList()
    if (providers.isEmpty() || providers.any { !isBinaryName(it) }) {
      problems += DistributionProblem.INVALID_SERVICE_DESCRIPTOR
      return emptySet()
    }
    return providers.toSet()
  }

  private fun parseBenchmarkList(bytes: ByteArray): Set<String>? {
    val content = decodeStrictUtf8(bytes) ?: return null
    val lines = content.lineSequence().filter(String::isNotBlank).toList()
    if (lines.isEmpty()) {
      return null
    }
    val benchmarks = lines.map(::parseBenchmarkLine)
    val parsed = benchmarks.takeIf { values -> values.none { it == null } }?.filterNotNull()
    return parsed?.takeIf { values -> values.distinct().size == values.size }?.toSet()
  }

  private fun parseBenchmarkLine(line: String): String? =
    runCatching {
        val reader = JmhLinePrefixReader(line)
        val userClass = reader.nextString()
        reader.nextString()
        val method = reader.nextString()
        "$userClass.$method"
      }
      .getOrNull()

  private fun readBoundedEntry(jar: JarFile, entry: JarEntry): ByteArray? {
    if (entry.size > MAX_METADATA_ENTRY_BYTES) {
      return null
    }
    return runCatching {
        jar.getInputStream(entry).use { input -> input.readNBytes(MAX_METADATA_ENTRY_BYTES + 1) }
      }
      .getOrNull()
      ?.takeIf { it.size <= MAX_METADATA_ENTRY_BYTES }
  }

  private fun hasValidEntryBytes(jar: JarFile, entry: JarEntry): Boolean =
    runCatching {
        val crc = CRC32()
        var bytesRead = 0L
        jar.getInputStream(entry).use { input ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          generateSequence { input.read(buffer).takeIf { it >= 0 } }
            .takeWhile { it != 0 }
            .forEach { count ->
              crc.update(buffer, 0, count)
              bytesRead += count
            }
        }
        entry.crc >= 0 && entry.size >= 0 && crc.value == entry.crc && bytesRead == entry.size
      }
      .getOrDefault(false)

  private fun hasClassFileMagic(jar: JarFile, entry: JarEntry): Boolean =
    runCatching {
        jar.getInputStream(entry).use { input -> input.readNBytes(CLASS_MAGIC.size) }
      }
      .getOrNull()
      ?.contentEquals(CLASS_MAGIC) == true

  private fun decodeStrictUtf8(bytes: ByteArray): String? =
    runCatching {
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString()
      }
      .getOrNull()

  private fun validJarEntryName(name: String): Boolean =
    name.isNotEmpty() &&
      !name.startsWith('/') &&
      !name.startsWith('\\') &&
      !name.contains('\\') &&
      !name.contains("//") &&
      name.split('/').none { it == "." || it == ".." }

  private fun isLoadableClassEntry(name: String): Boolean =
    name.endsWith(CLASS_SUFFIX) && !name.startsWith("META-INF/")

  private fun binaryIdentity(entryName: String): String =
    entryName.removeSuffix(CLASS_SUFFIX).replace('/', '.')

  private fun isBinaryName(value: String): Boolean =
    value.isNotEmpty() &&
      value.split('.').all { segment ->
        segment.isNotEmpty() &&
          Character.isJavaIdentifierStart(segment.first()) &&
          segment.drop(1).all(Character::isJavaIdentifierPart)
      }

  private class JmhLinePrefixReader(private val line: String) {
    private var cursor = JMH_MAGIC.length

    init {
      require(line.startsWith(JMH_MAGIC))
    }

    fun nextString(): String {
      require(line.getOrNull(cursor) == 'S')
      require(line.getOrNull(cursor + 1) == ' ')
      cursor += 2
      val lengthStart = cursor
      while (line.getOrNull(cursor)?.isDigit() == true) {
        cursor += 1
      }
      require(cursor > lengthStart && line.getOrNull(cursor) == ' ')
      val length = line.substring(lengthStart, cursor).toInt()
      cursor += 1
      val end = cursor + length
      require(end <= line.length)
      val value = line.substring(cursor, end)
      require(line.getOrNull(end) == ' ')
      cursor = end + 1
      return value
    }
  }

  private const val BENCHMARK_LIST = "META-INF/BenchmarkList"
  private const val COMPILER_HINTS = "META-INF/CompilerHints"
  private const val SERVICE_PREFIX = "META-INF/services/"
  private const val VERSIONED_PREFIX = "META-INF/versions/"
  private const val CLASS_SUFFIX = ".class"
  private const val JAR_TOOL_NAME = "jar"
  private const val MODULE_INFO = "module-info"
  private const val JMH_MAGIC = "JMH "
  private const val MIN_MULTI_RELEASE_VERSION = 9
  private const val MAX_JAR_ENTRIES = 250_000
  private const val MAX_METADATA_ENTRY_BYTES = 1024 * 1024
  private val VERSIONED_ENTRY = Regex("META-INF/versions/([0-9]+)/(.+)")
  private val CLASS_MAGIC =
    byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte())
}

private fun <T> immutableSet(values: Iterable<T>): Set<T> =
  java.util.Collections.unmodifiableSet(values.toSet())
