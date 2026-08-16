/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.json

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class BenchmarkJsonSchemaTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `schema validation accepts a conforming document`() {
        val document = writeDocument("valid.json", """{"id":"fixture"}""")

        BenchmarkJson.validateSchema(document, "/schema/id-record.schema.json")
    }

    @Test
    fun `schema validation rejects a nonconforming document`() {
        val document = writeDocument("invalid.json", """{"id":42}""")

        val failure = assertThrows<IllegalArgumentException> {
            BenchmarkJson.validateSchema(document, "/schema/id-record.schema.json")
        }

        assertThat(failure).hasMessageThat().contains("id-record.schema.json")
    }

    private fun writeDocument(name: String, contents: String): Path =
        temporaryDirectory.resolve(name).also { Files.writeString(it, contents) }
}
