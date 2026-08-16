/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.metrics

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/** Wraps one argument-list Java launch and parses peak resident set size from a private file. */
interface PeakRssProvider {
    val id: String
    val configurationSha256: String
    fun invocationPrefix(providerOutput: Path): List<String>
    fun parse(providerOutput: Path): Long
}

/** GNU time provider used by controlled Linux campaigns. */
object GnuTimePeakRssProvider : PeakRssProvider {
    override val id: String = "gnu-time-v-maximum-resident-set-kib/v1"
    override val configurationSha256: String = providerHash(id, listOf("/usr/bin/time", "-v", "-o"))

    override fun invocationPrefix(providerOutput: Path): List<String> =
        listOf("/usr/bin/time", "-v", "-o", normalizedOutput(providerOutput).toString())

    override fun parse(providerOutput: Path): Long {
        val kib = parseSingleLong(providerOutput, GNU_PATTERN, "GNU maximum resident set size")
        return try {
            Math.multiplyExact(kib, 1_024L)
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("GNU maximum resident set size overflow", failure)
        }
    }
}

/** BSD/macOS time provider used only for developer smoke runs. */
object MacOsTimePeakRssProvider : PeakRssProvider {
    override val id: String = "macos-time-l-maximum-resident-set-bytes/v1"
    override val configurationSha256: String = providerHash(id, listOf("/usr/bin/time", "-l", "-o"))

    override fun invocationPrefix(providerOutput: Path): List<String> =
        listOf("/usr/bin/time", "-l", "-o", normalizedOutput(providerOutput).toString())

    override fun parse(providerOutput: Path): Long =
        parseSingleLong(providerOutput, MACOS_PATTERN, "macOS maximum resident set size")
}

private fun normalizedOutput(path: Path): Path {
    require(path.isAbsolute && path.normalize() == path) {
        "Peak RSS provider output must be absolute and normalized: $path"
    }
    return path
}

private fun parseSingleLong(path: Path, pattern: Regex, label: String): Long {
    require(Files.isRegularFile(path)) { "$label provider output is missing: $path" }
    val values =
        Files.readAllLines(path).mapNotNull { line ->
            pattern.matchEntire(line)?.groupValues?.get(1)?.toLongOrNull()
        }
    require(values.size == 1) { "$label must appear exactly once in $path" }
    return values.single().also { require(it >= 0) { "$label must not be negative" } }
}

private fun providerHash(id: String, arguments: List<String>): String =
    ContentHasher.sha256((listOf(id) + arguments).joinToString("\u0000").toByteArray(UTF_8))

private val GNU_PATTERN = Regex("\\s*Maximum resident set size \\(kbytes\\):\\s*(\\d+)\\s*")
private val MACOS_PATTERN = Regex("\\s*(\\d+)\\s+maximum resident set size\\s*")
