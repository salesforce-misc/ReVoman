/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.compat

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class DetektBaselineIntegrityTest {
  @TempDir lateinit var temporaryDirectory: Path

  @Test
  fun `detekt baseline and every declaration source have exact reviewed bytes`() {
    DetektBaselineIntegrity.assertValid(Path.of("").toAbsolutePath().normalize())
  }

  @Test
  fun `integrity contract rejects body and baseline changes under an existing detekt ID`() {
    val source = temporaryDirectory.resolve("src/test/kotlin/example/Example.kt")
    val baseline = temporaryDirectory.resolve(BASELINE_PATH)
    Files.createDirectories(source.parent)
    Files.createDirectories(baseline.parent)
    Files.writeString(source, "class Example { fun longMethod() = 1 }\n")
    Files.writeString(
      baseline,
      "<SmellBaseline><ManuallySuppressedIssues></ManuallySuppressedIssues><CurrentIssues>" +
        "<ID>LongMethod:Example.kt:Example\$fun longMethod: Int</ID>" +
        "</CurrentIssues></SmellBaseline>\n",
    )
    val reviewedBaseline = Files.readString(baseline)
    writeInventory(temporaryDirectory, listOf(baseline, source))
    DetektBaselineIntegrity.assertValid(temporaryDirectory)

    Files.writeString(source, "class Example { fun longMethod() = 2 }\n")
    assertThrows<IllegalArgumentException> {
      DetektBaselineIntegrity.assertValid(temporaryDirectory)
    }
    Files.writeString(source, "class Example { fun longMethod() = 1 }\n")
    Files.writeString(baseline, reviewedBaseline.replace("LongMethod", "LargeClass"))
    assertThrows<IllegalArgumentException> {
      DetektBaselineIntegrity.assertValid(temporaryDirectory)
    }
    Files.writeString(
      baseline,
      reviewedBaseline.replace(
        "<ManuallySuppressedIssues>",
        "<ManuallySuppressedIssues><ID>LongMethod:Stale.kt\$stale</ID>",
      ),
    )
    writeInventory(temporaryDirectory, listOf(baseline, source))
    assertThrows<IllegalArgumentException> {
      DetektBaselineIntegrity.assertValid(temporaryDirectory)
    }
  }

  private fun writeInventory(root: Path, paths: List<Path>) {
    val inventory = root.resolve(INVENTORY_PATH)
    Files.writeString(
      inventory,
      paths
        .sortedBy { root.relativize(it).toString() }
        .joinToString(separator = "\n", postfix = "\n") { path ->
          "${DetektBaselineIntegrity.sha256(path)}  ${root.relativize(path)}"
        },
    )
  }

  private companion object {
    const val BASELINE_PATH = "detekt/baseline.xml"
    const val INVENTORY_PATH = "detekt/baseline-source-sha256sums.txt"
  }
}

private object DetektBaselineIntegrity {
  private val inventoryLine = Regex("([0-9a-f]{64})  ([A-Za-z0-9_./-]+)")
  private val baselineFilename = Regex("<ID>[^:]+:([^:\$]+\\.kt)")
  private val manualBaseline =
    Regex(
      "<ManuallySuppressedIssues>(.*?)</ManuallySuppressedIssues>",
      RegexOption.DOT_MATCHES_ALL,
    )

  fun assertValid(root: Path) {
    val expected = readInventory(root)
    val required = referencedSources(root) + setOf(Path.of("detekt/baseline.xml"))
    require(expected.keys == required) {
      "Detekt fingerprint paths differ: missing=${required - expected.keys}, extra=${expected.keys - required}"
    }
    expected.forEach { (relative, expectedHash) ->
      val actualHash = sha256(root.resolve(relative))
      require(actualHash == expectedHash) { "Detekt fingerprint changed for $relative" }
    }
  }

  fun sha256(path: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toHexString()

  private fun readInventory(root: Path): Map<Path, String> {
    val lines = Files.readAllLines(root.resolve("detekt/baseline-source-sha256sums.txt"))
    val rows = lines.map { line ->
      val match =
        requireNotNull(inventoryLine.matchEntire(line)) { "Invalid fingerprint row: $line" }
      val path = Path.of(match.groupValues[2]).normalize()
      require(!path.isAbsolute && !path.startsWith("..")) { "Unsafe fingerprint path: $path" }
      path to match.groupValues[1]
    }
    require(rows.map(Pair<Path, String>::first) == rows.map(Pair<Path, String>::first).sorted()) {
      "Detekt fingerprint paths are not sorted"
    }
    return rows.toMap().also { require(it.size == rows.size) { "Duplicate fingerprint path" } }
  }

  private fun referencedSources(root: Path): Set<Path> {
    val baseline = Files.readString(root.resolve("detekt/baseline.xml"))
    val manualBody = requireNotNull(manualBaseline.find(baseline)).groupValues[1]
    require("<ID>" !in manualBody) { "Detekt manual baseline IDs are forbidden" }
    val filenames = baselineFilename.findAll(baseline).map { it.groupValues[1] }.toSet()
    val paths =
      Files.walk(root.resolve("src")).use { stream ->
        stream
          .filter { Files.isRegularFile(it) && it.fileName.toString() in filenames }
          .map(root::relativize)
          .toList()
          .toSet()
      }
    require(paths.map { it.fileName.toString() }.toSet() == filenames) {
      "A Detekt baseline source file is missing"
    }
    return paths
  }
}
