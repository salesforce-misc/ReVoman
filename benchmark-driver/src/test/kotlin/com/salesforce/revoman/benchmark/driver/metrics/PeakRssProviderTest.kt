/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.metrics

import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PeakRssProviderTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `GNU time converts maximum RSS KiB to bytes and isolates provider output`() {
        val output = resourcePath("/metrics/gnu-time-linux.txt")
        val providerOutput = temporaryDirectory.resolve("gnu-time.txt")
        val javaCommand = listOf("/jdk/bin/java", "-cp", "/bench/classes", "example.Main")

        val wrapped = GnuTimePeakRssProvider.wrap(javaCommand, providerOutput)

        assertThat(GnuTimePeakRssProvider.parse(output)).isEqualTo(12_641_280L)
        assertThat(wrapped)
            .containsExactly(
                "/usr/bin/time",
                "-v",
                "-o",
                providerOutput.toString(),
                *javaCommand.toTypedArray(),
            )
            .inOrder()
        assertThat(wrapped).doesNotContain("2>")
        assertThat(GnuTimePeakRssProvider.id).isNotEmpty()
        assertThat(GnuTimePeakRssProvider.configurationSha256).hasLength(64)
    }

    @Test
    fun `macOS time keeps maximum RSS bytes and isolates provider output`() {
        val output = resourcePath("/metrics/bsd-time-macos.txt")
        val providerOutput = temporaryDirectory.resolve("bsd-time.txt")
        val javaCommand = listOf("/jdk/bin/java", "example.Main")

        val wrapped = MacOsTimePeakRssProvider.wrap(javaCommand, providerOutput)

        assertThat(MacOsTimePeakRssProvider.parse(output)).isEqualTo(98_765_432L)
        assertThat(wrapped)
            .containsExactly(
                "/usr/bin/time",
                "-l",
                "-o",
                providerOutput.toString(),
                *javaCommand.toTypedArray(),
            )
            .inOrder()
        assertThat(wrapped).doesNotContain("2>")
        assertThat(MacOsTimePeakRssProvider.id).isNotEmpty()
        assertThat(MacOsTimePeakRssProvider.configurationSha256).hasLength(64)
    }

    @Test
    fun `GNU time rejects KiB to byte overflow`() {
        val output = temporaryDirectory.resolve("overflow.txt")
        java.nio.file.Files.writeString(
            output,
            "Maximum resident set size (kbytes): ${Long.MAX_VALUE}",
        )

        val failure = assertThrows<IllegalArgumentException> {
            GnuTimePeakRssProvider.parse(output)
        }

        assertThat(failure).hasMessageThat().contains("overflow")
    }

    private fun resourcePath(name: String): Path =
        Path.of(requireNotNull(javaClass.getResource(name)) { "Missing test resource: $name" }.toURI())
}
