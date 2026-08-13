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
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import org.w3c.dom.Node

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
    Files.writeString(
      baseline,
      reviewedBaseline.replace(
        "<ID>LongMethod:Example.kt:Example\$fun longMethod: Int</ID>",
        "<ID>LongMethod:Example.kt:prefix" +
          "<ID>LongMethod:Untracked.kt\$hidden</ID>" +
          "LongMethod:Other.kt\$real</ID>",
      ),
    )
    writeInventory(temporaryDirectory, listOf(baseline, source))
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
        "<ManuallySuppressedIssues><ID >LongMethod:Stale.kt\$stale</ID>",
      ),
    )
    writeInventory(temporaryDirectory, listOf(baseline, source))
    assertThrows<IllegalArgumentException> {
      DetektBaselineIntegrity.assertValid(temporaryDirectory)
    }
    Files.writeString(
      baseline,
      reviewedBaseline.replace(
        "<ID>LongMethod:Example.kt",
        "<ID >LongMethod:Untracked.kt",
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
  private val baselineFilename = Regex("^[^:]+:([^:\$]+\\.kt)(?=[:\$])")

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
    val (manualIds, currentIds) = readBaselineIds(root.resolve("detekt/baseline.xml"))
    require(manualIds.isEmpty()) { "Detekt manual baseline IDs are forbidden" }
    val filenames =
      currentIds
        .map { id ->
          requireNotNull(baselineFilename.find(id)) { "Invalid Detekt baseline ID: $id" }
            .groupValues[1]
        }
        .toSet()
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

  private fun readBaselineIds(path: Path): BaselineIds {
    val factory =
      DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
      }
    val document = Files.newInputStream(path).use { factory.newDocumentBuilder().parse(it) }
    val root = document.documentElement
    require(root.tagName == "SmellBaseline" && root.attributes.length == 0) {
      "Invalid Detekt baseline root"
    }
    val sections = childElements(root)
    require(
      sections.map(Element::getTagName) == listOf("ManuallySuppressedIssues", "CurrentIssues")
    ) {
      "Invalid Detekt baseline sections"
    }
    val manualIds = readIds(sections[0])
    val currentIds = readIds(sections[1])
    require(currentIds.isNotEmpty() && currentIds == currentIds.sorted()) {
      "Detekt current baseline IDs must be nonempty and sorted"
    }
    return BaselineIds(manualIds, currentIds)
  }

  private fun readIds(section: Element): List<String> {
    require(section.attributes.length == 0) { "Detekt baseline section has attributes" }
    require(
      (0 until section.childNodes.length).all { index ->
        val child = section.childNodes.item(index)
        child is Element || child.nodeType == Node.TEXT_NODE && child.textContent.isBlank()
      }
    ) {
      "Detekt baseline section has unexpected content"
    }
    val elements = childElements(section)
    require(elements.all { it.tagName == "ID" && it.attributes.length == 0 }) {
      "Detekt baseline section contains a non-ID element"
    }
    val ids = elements.map { element ->
      require(element.childNodes.length == 1 && element.firstChild.nodeType == Node.TEXT_NODE) {
        "Detekt baseline ID must contain one plain text node"
      }
      element.textContent.also { require(it.isNotEmpty() && it == it.trim()) }
    }
    require(ids.distinct().size == ids.size) {
      "Detekt baseline IDs must be nonempty and unique"
    }
    return ids
  }

  private fun childElements(parent: Element): List<Element> =
    (0 until parent.childNodes.length).mapNotNull { parent.childNodes.item(it) as? Element }

  private data class BaselineIds(val manual: List<String>, val current: List<String>)
}
