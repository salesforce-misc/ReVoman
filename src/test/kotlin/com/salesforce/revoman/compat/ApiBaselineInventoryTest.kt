/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.compat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ApiBaselineInventoryTest {
  @Test
  fun `CS2a migration docs identify unsupported internals and correct benchmark pairing`() {
    val migrationGuide = normalizedWhitespace(requiredText(MIGRATION_GUIDE))
    val scriptsGuide = normalizedWhitespace(requiredText(SCRIPTS_GUIDE))
    val readme = normalizedWhitespace(requiredText(README))
    val development = normalizedWhitespace(requiredText(DEVELOPMENT))

    assertThat(migrationGuide)
      .contains("`ExecutionSession`, `KickExecution`, and `ExecutionLifecycleDiagnostics`")
    assertThat(migrationGuide).contains("unsupported internal implementation details")
    assertThat(migrationGuide).contains("CS2a changes no serialized report or schema field")
    assertThat(migrationGuide).contains("CS2b, CS2c, and CS2d remain pending")
    assertThat(scriptsGuide).contains("xref:migration-guide.adoc[major-version migration guide]")
    assertThat(readme)
      .contains(
        "https://salesforce-misc.github.io/ReVoman/revoman/migration-guide.html" +
          "[Major-version migration guide]"
      )
    assertThat(development).contains("build/benchmark-target-current.json` with `major-v1`")
    assertThat(development)
      .contains("build/benchmark-target-baseline-selftest.json` with `baseline-83f3cd70`")
    assertThat(development).contains("complete driver integration suite and harness self-test")
    assertThat(development).contains("two targeted current lifecycle integration tests")
  }

  @Test
  fun `non ABI contract gate rejects deleted behavior and stale exceptional JSON rows`() {
    val spec = requiredText(UMBRELLA_SPEC)
    val rows = parseMigrationRows(requiredText(MIGRATION_LEDGER))
    val firstBehavior = rows.first { it.kind == "behavior" }
    val staleJson =
      MigrationRow(
        kind = "json",
        legacyId = "Stale:/removed",
        owner = "CS4",
        disposition = "removed",
        replacementId = "StaleReplacement",
      )

    assertThrows<AssertionError> {
      assertCurrentNonAbiCoverage(rows.filterNot { it == firstBehavior }, spec)
    }
    assertThrows<AssertionError> { assertCurrentNonAbiCoverage(rows + staleJson, spec) }
  }

  @Test
  fun `normative JSON crosswalk rejects swapped mappings and owner drift`() {
    val spec = requiredText(UMBRELLA_SPEC)
    val rows = parseMigrationRows(requiredText(MIGRATION_LEDGER))
    val firstIndex = rows.indexOfFirst {
      it.replacementId == "RundownV2:/providedStepsToExecuteCount"
    }
    val secondIndex = rows.indexOfFirst { it.replacementId == "RundownV2:/executedStepCount" }
    assertThat(firstIndex).isAtLeast(0)
    assertThat(secondIndex).isAtLeast(0)

    val swappedRows = rows.toMutableList()
    val first = swappedRows[firstIndex]
    val second = swappedRows[secondIndex]
    swappedRows[firstIndex] = first.copy(replacementId = second.replacementId)
    swappedRows[secondIndex] = second.copy(replacementId = first.replacementId)
    assertThrows<AssertionError> { assertCurrentNonAbiCoverage(swappedRows, spec) }

    val ownerDriftRows = rows.toMutableList()
    ownerDriftRows[firstIndex] = first.copy(owner = "CS5")
    assertThrows<AssertionError> { assertCurrentNonAbiCoverage(ownerDriftRows, spec) }

    val ledgerIndex = rows.indexOfFirst { it.replacementId == "LedgerV2:/schemaVersion" }
    assertThat(ledgerIndex).isAtLeast(0)
    val ledgerOwnerDriftRows = rows.toMutableList()
    ledgerOwnerDriftRows[ledgerIndex] = ledgerOwnerDriftRows[ledgerIndex].copy(owner = "CS4")
    assertThrows<AssertionError> { assertCurrentNonAbiCoverage(ledgerOwnerDriftRows, spec) }
  }

  @Test
  fun `Kotlin ABI baseline is immutable complete and independently checksummed`() {
    val active = requiredText(ACTIVE_KOTLIN_ABI)
    val baseline = requiredText(FROZEN_KOTLIN_ABI)

    assertThat(active).isNotEmpty()
    assertThat(baseline).isNotEmpty()
    assertThat(sha256(baseline)).isEqualTo(FROZEN_KOTLIN_ABI_SHA256)
    requiredBaselineSymbols.forEach { symbol ->
      assertWithMessage("missing frozen Kotlin ABI symbol: $symbol").that(baseline).contains(symbol)
    }

    val postmanOwners = normalizedKotlinDeclarations(baseline, CS2A_POSTMAN_OWNERS)
    assertThat(postmanOwners.asSequence().map { it.substringBefore('#') }.toSet())
      .containsExactlyElementsIn(CS2A_POSTMAN_OWNERS)
  }

  @Test
  fun `JVM baseline is canonical complete and active raw additions are exactly approved`() {
    val frozenText = requiredText(FROZEN_JVM_ABI)
    val frozen = JvmSurfaceInventory.parse(frozenText)

    assertThat(sha256(frozenText)).isEqualTo(FROZEN_JVM_ABI_SHA256)
    assertThat(frozen).hasSize(FROZEN_JVM_ENTRY_COUNT)
    assertThat(JvmSurfaceInventory.render(frozen)).isEqualTo(frozenText)
    assertThat(
        frozen
          .asSequence()
          .filter { it.kind == JvmSurfaceKind.CLASS }
          .map(JvmSurfaceEntry::owner)
          .toSet()
      )
      .containsAtLeastElementsIn(CS2A_POSTMAN_OWNERS)

    val active = JvmSurfaceInventory.readJar(configuredRootJar())
    val activeRows = active.asSequence().map(JvmSurfaceEntry::render).toSet()
    val frozenRows = frozen.asSequence().map(JvmSurfaceEntry::render).toSet()
    val rawRemovals = frozenRows - activeRows
    val rawAdditions = activeRows - frozenRows
    assertThat(rawRemovals).hasSize(CS2A_RAW_JVM_REMOVAL_COUNT)
    assertThat(rawAdditions).hasSize(CS2A_RAW_JVM_ADDITION_COUNT)
    assertThat(CS2_TASK7_RAW_JVM_REMOVALS).hasSize(CS2A_RAW_JVM_REMOVAL_COUNT)
    assertThat(CS2_TASK7_RAW_JVM_ADDITIONS).hasSize(CS2A_RAW_JVM_ADDITION_COUNT)
    assertThat(rawRemovals).containsExactlyElementsIn(CS2_TASK7_RAW_JVM_REMOVALS)
    assertThat(rawAdditions).containsExactlyElementsIn(CS2_TASK7_RAW_JVM_ADDITIONS)
  }

  @Test
  fun `CS2a ABI projections reject missing and stale rows in every ABI domain`() {
    val baselineKotlin = requiredText(FROZEN_KOTLIN_ABI)
    val activeKotlin = requiredText(ACTIVE_KOTLIN_ABI)
    val baselineJvm = JvmSurfaceInventory.parse(requiredText(FROZEN_JVM_ABI))
    val activeJvm = JvmSurfaceInventory.readJar(configuredRootJar())
    val rows = parseMigrationRows(requiredText(MIGRATION_LEDGER))
    val baselineJvmByKey = baselineJvm.associateBy { it.migrationKey() }
    val representativeRows =
      listOf(
        rows.first {
          it.owner == "CS2a" && it.kind == "kotlin" && it.disposition in ABI_REMOVAL_DISPOSITIONS
        },
        rows.first {
          it.owner == "CS2a" &&
            it.kind == "java" &&
            it.disposition in ABI_REMOVAL_DISPOSITIONS &&
            baselineJvmByKey[it.legacyId]?.sourceCallable == true
        },
        rows.first {
          it.owner == "CS2a" &&
            it.kind == "java" &&
            it.disposition in ABI_REMOVAL_DISPOSITIONS &&
            baselineJvmByKey[it.legacyId]?.let { entry ->
              entry.memberSynthetic || entry.bridge
            } == true
        },
      )

    representativeRows.forEach { row ->
      assertThrows<AssertionError> {
        assertExactCs2aAbiProjections(
          baselineKotlin,
          activeKotlin,
          baselineJvm,
          activeJvm,
          rows - row,
        )
      }
      assertThrows<AssertionError> {
        assertExactCs2aAbiProjections(
          baselineKotlin,
          activeKotlin,
          baselineJvm,
          activeJvm,
          rows + row.copy(legacyId = "${row.legacyId}#stale"),
        )
      }
    }
  }

  @Test
  fun `CS2a Java addition projection rejects a newly public class`() {
    val baselineKotlin = requiredText(FROZEN_KOTLIN_ABI)
    val activeKotlin = requiredText(ACTIVE_KOTLIN_ABI)
    val baselineJvm = JvmSurfaceInventory.parse(requiredText(FROZEN_JVM_ABI))
    val activeJvm = JvmSurfaceInventory.readJar(configuredRootJar())
    val rows = parseMigrationRows(requiredText(MIGRATION_LEDGER))
    val publicClassOwner = "com/salesforce/revoman/internal/runtime/AccidentallyPublicLifecycleType"
    val publicClassAddition =
      JvmSurfaceEntry(
        owner = publicClassOwner,
        kind = JvmSurfaceKind.CLASS,
        name = "<class>",
        descriptor = "L$publicClassOwner;",
        ownerAccess = 0x0001,
        memberAccess = 0,
        ownerSynthetic = false,
        memberSynthetic = false,
        bridge = false,
        sourceCallable = true,
      )

    assertThrows<AssertionError> {
      assertExactCs2aAbiProjections(
        baselineKotlin,
        activeKotlin,
        baselineJvm,
        activeJvm + publicClassAddition,
        rows,
      )
    }
  }

  @Test
  fun `migration ledger exactly covers CS2a ABI removals and approved redesign contracts`() {
    val baselineKotlin = requiredText(FROZEN_KOTLIN_ABI)
    val activeKotlin = requiredText(ACTIVE_KOTLIN_ABI)
    val baselineJvm = JvmSurfaceInventory.parse(requiredText(FROZEN_JVM_ABI))
    val activeJvm = JvmSurfaceInventory.readJar(configuredRootJar())
    val rows = parseMigrationRows(requiredText(MIGRATION_LEDGER))

    assertThat(rows.map { it.kind to it.legacyId }.toSet()).hasSize(rows.size)
    rows.forEach { row ->
      assertWithMessage("blank migration row: $row")
        .that(
          listOf(row.kind, row.legacyId, row.owner, row.disposition, row.replacementId).all {
            it.isNotBlank()
          }
        )
        .isTrue()
      assertThat(row.kind).isIn(ALLOWED_KINDS)
      assertThat(row.owner).isIn(ALLOWED_OWNERS)
      assertThat(row.disposition).isIn(ALLOWED_DISPOSITIONS)
    }
    assertCurrentNonAbiCoverage(rows, requiredText(UMBRELLA_SPEC))

    assertExactCs2aAbiProjections(
      baselineKotlin,
      activeKotlin,
      baselineJvm,
      activeJvm,
      rows,
    )
    val activeJvmRows = activeJvm.asSequence().map(JvmSurfaceEntry::render).toSet()
    val baselineJvmRows = baselineJvm.asSequence().map(JvmSurfaceEntry::render).toSet()
    val rawRemovals = baselineJvmRows - activeJvmRows
    val rawAdditions = activeJvmRows - baselineJvmRows
    assertThat(rawRemovals).hasSize(CS2A_RAW_JVM_REMOVAL_COUNT)
    assertThat(rawAdditions).hasSize(CS2A_RAW_JVM_ADDITION_COUNT)
    assertThat(rawRemovals).containsExactlyElementsIn(CS2_TASK7_RAW_JVM_REMOVALS)
    assertThat(rawAdditions).containsExactlyElementsIn(CS2_TASK7_RAW_JVM_ADDITIONS)
  }

  private fun assertExactCs2aAbiProjections(
    baselineKotlin: String,
    activeKotlin: String,
    baselineJvm: List<JvmSurfaceEntry>,
    activeJvm: List<JvmSurfaceEntry>,
    rows: List<MigrationRow>,
  ) {
    val baselineKotlinDeclarations = normalizedKotlinDeclarations(baselineKotlin)
    val activeKotlinDeclarations = normalizedKotlinDeclarations(activeKotlin)
    val cs2aKotlinProjection =
      rows
        .asSequence()
        .filter {
          it.owner == "CS2a" && it.kind == "kotlin" && it.disposition in ABI_REMOVAL_DISPOSITIONS
        }
        .map(MigrationRow::legacyId)
        .toSet()
    assertThat(cs2aKotlinProjection).hasSize(CS2A_KOTLIN_REMOVAL_COUNT)
    assertThat(baselineKotlinDeclarations - activeKotlinDeclarations)
      .containsExactlyElementsIn(cs2aKotlinProjection)
    assertThat(activeKotlinDeclarations - baselineKotlinDeclarations).isEmpty()

    val baselineJvmByKey = baselineJvm.associateBy { it.migrationKey() }
    assertThat(baselineJvmByKey).hasSize(baselineJvm.size)
    val cs2aJavaProjection =
      rows
        .asSequence()
        .filter {
          it.owner == "CS2a" && it.kind == "java" && it.disposition in ABI_REMOVAL_DISPOSITIONS
        }
        .map(MigrationRow::legacyId)
        .toSet()
    val sourceCallableProjection =
      cs2aJavaProjection.filterTo(linkedSetOf()) { key ->
        baselineJvmByKey[key]?.sourceCallable == true
      }
    val syntheticBridgeProjection =
      cs2aJavaProjection.filterTo(linkedSetOf()) { key ->
        baselineJvmByKey[key]?.let { it.memberSynthetic || it.bridge } == true
      }
    assertThat(sourceCallableProjection).hasSize(CS2A_JAVA_SOURCE_CALLABLE_REMOVAL_COUNT)
    assertThat(syntheticBridgeProjection).hasSize(CS2A_JAVA_SYNTHETIC_BRIDGE_REMOVAL_COUNT)
    assertThat(sourceCallableProjection.intersect(syntheticBridgeProjection)).isEmpty()
    assertThat(sourceCallableProjection + syntheticBridgeProjection)
      .containsExactlyElementsIn(cs2aJavaProjection)

    val baselineJavaSourceCallable =
      baselineJvm
        .asSequence()
        .filter(JvmSurfaceEntry::sourceCallable)
        .map {
          it.migrationKey()
        }
        .toSet()
    val activeJavaSourceCallable =
      activeJvm
        .asSequence()
        .filter(JvmSurfaceEntry::sourceCallable)
        .map {
          it.migrationKey()
        }
        .toSet()
    assertThat(baselineJavaSourceCallable - activeJavaSourceCallable)
      .containsExactlyElementsIn(sourceCallableProjection)
    val supportedJavaAdditions =
      activeJvm
        .asSequence()
        .filter(JvmSurfaceEntry::sourceCallable)
        .map { it.migrationKey() }
        .toSet() -
        baselineJvm
          .asSequence()
          .filter(JvmSurfaceEntry::sourceCallable)
          .map { it.migrationKey() }
          .toSet()
    val approvedJavaAdditionEntries =
      CS2_TASK7_RAW_JVM_ADDITIONS.asSequence()
        .map(JvmSurfaceEntry::parse)
        .filter(JvmSurfaceEntry::sourceCallable)
        .toSet()
    assertThat(approvedJavaAdditionEntries).hasSize(CS2A_JAVA_SOURCE_CALLABLE_ADDITION_COUNT)
    assertThat(approvedJavaAdditionEntries.map(JvmSurfaceEntry::kind).toSet())
      .containsExactly(JvmSurfaceKind.CLASS)
    val approvedJavaAdditions = approvedJavaAdditionEntries.map { it.migrationKey() }.toSet()
    assertThat(supportedJavaAdditions).containsExactlyElementsIn(approvedJavaAdditions)

    val activeRawKeys = activeJvm.asSequence().map { it.migrationKey() }.toSet()
    val sourceCallableRemovalOwners =
      sourceCallableProjection.mapTo(linkedSetOf()) { it.substringBefore('|') }
    val removedSyntheticBridgeKeys =
      baselineJvm
        .asSequence()
        .filter {
          it.owner in sourceCallableRemovalOwners && (it.memberSynthetic || it.bridge)
        }
        .map { it.migrationKey() }
        .filter { it !in activeRawKeys }
        .toSet()
    assertThat(removedSyntheticBridgeKeys).containsExactlyElementsIn(syntheticBridgeProjection)
  }

  private fun assertCurrentNonAbiCoverage(rows: List<MigrationRow>, spec: String) {
    val normativeSchemaRows = parseNormativeSchemaRows(spec)
    assertThat(normativeSchemaRows).hasSize(NORMATIVE_V2_FIELD_COUNT)
    assertCanonicalJsonPointers("version-2 schema", normativeSchemaRows)
    val normativeReplacementIds = normativeSchemaRows.map { (document, pointer) ->
      "${document}V2:$pointer"
    }
    val normativeCrosswalkRows = parseNormativeSchemaCrosswalkRows(spec)
    assertThat(normativeCrosswalkRows).hasSize(NORMATIVE_V2_FIELD_COUNT)
    assertThat(normativeCrosswalkRows.map(MigrationRow::kind).toSet()).containsExactly("json")
    assertThat(normativeCrosswalkRows.map(MigrationRow::replacementId))
      .containsExactlyElementsIn(normativeReplacementIds)
    val normativeLedgerRows = rows.filter {
      it.kind == "json" && it.replacementId in normativeReplacementIds
    }
    assertThat(normativeLedgerRows).containsExactlyElementsIn(normativeCrosswalkRows)

    val sourceDerivedRows = parseSourceDerivedSchemaRows(spec)
    assertThat(sourceDerivedRows).hasSize(SOURCE_DERIVED_FIELD_COUNT)
    assertCanonicalJsonPointers("source-derived legacy schema", sourceDerivedRows)
    val sourceDerivedLegacyIds = sourceDerivedRows.map { (document, pointer) ->
      "$document:$pointer"
    }
    val sourceDerivedLedgerRows = rows.filter {
      it.kind == "json" && it.legacyId in sourceDerivedLegacyIds
    }
    assertThat(sourceDerivedLedgerRows.map(MigrationRow::legacyId))
      .containsExactlyElementsIn(sourceDerivedLegacyIds)
    assertThat(rows.filter { it.kind == "json" }.map(MigrationRow::legacyId))
      .doesNotContain("Rundown:/stopReason")

    val normativeNonAbiRows = parseNormativeNonAbiRows(spec)
    assertThat(normativeNonAbiRows).hasSize(NORMATIVE_NON_ABI_CONTRACT_COUNT)
    val actualNonAbiRows = rows.filter {
      it.kind == "behavior" || (it.kind == "json" && it.replacementId !in normativeReplacementIds)
    }
    assertThat(actualNonAbiRows).containsExactlyElementsIn(normativeNonAbiRows)
  }

  private fun requiredText(path: Path): String {
    assertWithMessage("missing checked-in inventory: $path")
      .that(Files.isRegularFile(path))
      .isTrue()
    return Files.readString(path, StandardCharsets.UTF_8).also {
      assertWithMessage("empty checked-in inventory: $path").that(it).isNotEmpty()
    }
  }

  private fun normalizedWhitespace(text: String): String = text.replace(Regex("\\s+"), " ")

  private fun parseMigrationRows(text: String): List<MigrationRow> {
    val lines = text.split('\n').filter(String::isNotEmpty)
    assertThat(lines.firstOrNull()).isEqualTo(MIGRATION_HEADER.joinToString("\t"))
    return lines.drop(1).map { line ->
      val columns = line.split('\t')
      assertWithMessage("invalid migration row: $line").that(columns).hasSize(MIGRATION_HEADER.size)
      MigrationRow(
        kind = columns[0],
        legacyId = columns[1],
        owner = columns[2],
        disposition = columns[3],
        replacementId = columns[4],
      )
    }
  }

  private fun normalizedKotlinDeclarations(
    text: String,
    selectedOwners: Set<String>? = null,
  ): Set<String> {
    val declarations = linkedSetOf<String>()
    var owner: String? = null
    text.lineSequence().forEach { rawLine ->
      when {
        rawLine.startsWith("public ") && " class " in rawLine -> {
          owner = rawLine.substringAfterLast(" class ").substringBefore(' ').substringBefore(':')
          if (selectedOwners == null || owner in selectedOwners) {
            declarations += "$owner#${rawLine.trim()}"
          }
        }
        rawLine.startsWith('\t') && owner != null ->
          if (selectedOwners == null || owner in selectedOwners) {
            declarations += "$owner#${rawLine.trim()}"
          }
        rawLine == "}" -> owner = null
      }
    }
    return declarations
  }

  private fun parseNormativeSchemaRows(text: String): List<Pair<String, String>> {
    return parseSchemaTable(text, NORMATIVE_SCHEMA_HEADER, 4)
  }

  private fun parseNormativeSchemaCrosswalkRows(text: String): List<MigrationRow> {
    return parseMigrationTable(text, NORMATIVE_SCHEMA_CROSSWALK_HEADER)
  }

  private fun parseSourceDerivedSchemaRows(text: String): List<Pair<String, String>> {
    return parseSchemaTable(text, SOURCE_DERIVED_SCHEMA_HEADER, 2)
  }

  private fun parseNormativeNonAbiRows(text: String): List<MigrationRow> {
    return parseMigrationTable(text, NORMATIVE_NON_ABI_HEADER)
  }

  private fun parseMigrationTable(text: String, header: String): List<MigrationRow> {
    return parseMarkdownTable(text, header, 5).map { columns ->
      MigrationRow(
        kind = columns[0],
        legacyId = columns[1].removeSurrounding("`"),
        owner = columns[2],
        disposition = columns[3],
        replacementId = columns[4].removeSurrounding("`"),
      )
    }
  }

  private fun parseSchemaTable(
    text: String,
    header: String,
    columnCount: Int,
  ): List<Pair<String, String>> {
    return parseMarkdownTable(text, header, columnCount).map { columns ->
      columns[0] to columns[1].removeSurrounding("`")
    }
  }

  private fun parseMarkdownTable(
    text: String,
    header: String,
    columnCount: Int,
  ): List<List<String>> {
    return text
      .lineSequence()
      .dropWhile { it != header }
      .drop(2)
      .takeWhile { it.startsWith('|') }
      .map { row ->
        row.removePrefix("|").removeSuffix("|").split('|').map(String::trim).also { columns ->
          assertWithMessage("invalid Markdown table row below '$header': $row")
            .that(columns)
            .hasSize(columnCount)
        }
      }
      .toList()
  }

  private fun assertCanonicalJsonPointers(label: String, rows: List<Pair<String, String>>) {
    assertWithMessage("duplicate $label document/pointer rows")
      .that(rows.toSet())
      .hasSize(rows.size)
    rows.forEach { (document, pointer) ->
      assertWithMessage("blank $label document for $pointer").that(document).isNotEmpty()
      assertWithMessage("invalid $label JSON pointer: $pointer")
        .that(pointer.matches(JSON_POINTER_PATTERN))
        .isTrue()
    }
  }

  private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(text.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { byte -> String.format(Locale.ROOT, "%02x", byte) }

  private fun JvmSurfaceEntry.migrationKey(): String =
    render().split('\t').take(6).joinToString("|")

  private data class MigrationRow(
    val kind: String,
    val legacyId: String,
    val owner: String,
    val disposition: String,
    val replacementId: String,
  )

  private companion object {
    val ACTIVE_KOTLIN_ABI: Path = Path.of("api/revoman-root.api")
    val FROZEN_KOTLIN_ABI: Path = Path.of("api/cs2-baseline-revoman-root.api")
    val FROZEN_JVM_ABI: Path = Path.of("api/cs2-baseline-revoman-root.jvm.tsv")
    val MIGRATION_LEDGER: Path = Path.of("api/cs2-migration-map.tsv")
    val MIGRATION_GUIDE: Path = Path.of("docs/modules/ROOT/pages/migration-guide.adoc")
    val SCRIPTS_GUIDE: Path = Path.of("docs/modules/ROOT/pages/scripts-and-pm-apis.adoc")
    val README: Path = Path.of("README.adoc")
    val DEVELOPMENT: Path = Path.of("DEVELOPMENT.md")
    val UMBRELLA_SPEC: Path =
      Path.of("docs/superpowers/specs/2026-08-09-performance-redesign-design.md")
    const val FROZEN_KOTLIN_ABI_SHA256 =
      "3cbe0a2168e4db655d60b49e8f00d66d8951aca9ab08d64176dca5bc72cbf4fa"
    const val FROZEN_JVM_ABI_SHA256 =
      "6ecd4fd73461ed3353148cbb34a2cea1f60ba3bed256af7ed4dfeacfdaac1d2f"
    const val FROZEN_JVM_ENTRY_COUNT = 6101
    const val NORMATIVE_V2_FIELD_COUNT = 151
    const val SOURCE_DERIVED_FIELD_COUNT = 66
    const val NORMATIVE_NON_ABI_CONTRACT_COUNT = 54
    const val CS2A_KOTLIN_REMOVAL_COUNT = 73
    const val CS2A_JAVA_SOURCE_CALLABLE_REMOVAL_COUNT = 100
    const val CS2A_JAVA_SYNTHETIC_BRIDGE_REMOVAL_COUNT = 15
    const val CS2A_JAVA_SOURCE_CALLABLE_ADDITION_COUNT = 28
    const val CS2A_RAW_JVM_ADDITION_COUNT = 549
    const val CS2A_RAW_JVM_REMOVAL_COUNT = 447
    const val NORMATIVE_SCHEMA_HEADER =
      "| Document | Instance JSON pointer | Version-2 schema | Presence |"
    const val NORMATIVE_SCHEMA_CROSSWALK_HEADER =
      "| Normative kind | Legacy ID | Owner | Disposition | Replacement ID |"
    const val SOURCE_DERIVED_SCHEMA_HEADER = "| Legacy document | Source-derived JSON pointer |"
    const val NORMATIVE_NON_ABI_HEADER =
      "| Kind | Legacy ID | Owner | Disposition | Replacement ID |"
    val JSON_POINTER_PATTERN = Regex("/(?:[^/~]|~[01])+(?:/(?:[^/~]|~[01])+)*")
    val MIGRATION_HEADER = listOf("kind", "legacyId", "owner", "disposition", "replacementId")
    val ALLOWED_KINDS = setOf("kotlin", "java", "json", "behavior")
    val ALLOWED_OWNERS = setOf("CS2a", "CS2b", "CS2c", "CS2d", "CS3", "CS4", "CS5", "CS6")
    val ALLOWED_DISPOSITIONS =
      setOf("removed", "internalized", "replaced", "deprecated", "versioned", "bounded", "retained")
    val ABI_REMOVAL_DISPOSITIONS = setOf("removed", "internalized")
    val CS2A_POSTMAN_OWNERS =
      setOf(
        "com/salesforce/revoman/internal/postman/PostmanSDK",
        "com/salesforce/revoman/internal/postman/PostmanSDK\$JSEvaluator",
        "com/salesforce/revoman/internal/postman/PostmanSDK\$Variables",
        "com/salesforce/revoman/internal/postman/PostmanSDK\$Request",
        "com/salesforce/revoman/internal/postman/PostmanSDK\$Response",
        "com/salesforce/revoman/internal/postman/PostmanSDK\$Xml2Json",
        "com/salesforce/revoman/internal/postman/Info",
        "com/salesforce/revoman/internal/postman/RegexReplacer",
        "com/salesforce/revoman/internal/postman/RegexReplacer\$Companion",
      )
    val requiredBaselineSymbols =
      listOf(
        "public final class com/salesforce/revoman/ReVoman",
        "public final class com/salesforce/revoman/internal/postman/PostmanSDK",
        "com/salesforce/revoman/internal/postman/PostmanSDK\$JSEvaluator",
        "com/salesforce/revoman/internal/postman/PostmanSDK\$Variables",
        "com/salesforce/revoman/internal/postman/PostmanSDK\$Request",
        "com/salesforce/revoman/internal/postman/PostmanSDK\$Response",
        "com/salesforce/revoman/internal/postman/PostmanSDK\$Xml2Json",
        "com/salesforce/revoman/internal/postman/Info",
        "com/salesforce/revoman/internal/postman/RegexReplacer",
        "public abstract interface class com/salesforce/revoman/input/PostExeHook",
        "public abstract interface class com/salesforce/revoman/output/log/RunLogSink",
      )
  }
}
