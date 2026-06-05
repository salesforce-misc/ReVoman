/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input

import com.google.common.truth.Truth.assertThat
import io.kotest.matchers.string.shouldNotBeBlank
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Test

class FileUtilsTest {
  @Test
  fun `read file from resources to string`() {
    readFileToString("env-with-regex.json").shouldNotBeBlank()
  }

  @Test
  fun `readGzippedFileToString inflates a gzipped classpath resource`() {
    val content = readGzippedFileToString("gzip-fixture.txt.gz")
    assertThat(content).isEqualTo("hello gzip world\nline two with vic.gov.au token\n")
  }

  @Test
  fun `read file to string`() {
    val file = File("src/test/resources/env-with-regex.json")
    readFileToString(file).shouldNotBeBlank()
  }

  @Test
  fun testIsV3EnvFileTruthTable() {
    assertThat(isV3EnvFile("env.yaml")).isTrue()
    assertThat(isV3EnvFile("env.yml")).isTrue()
    assertThat(isV3EnvFile("env.YAML")).isFalse()
    assertThat(isV3EnvFile("env.json")).isFalse()
    assertThat(isV3EnvFile("env")).isFalse()
    assertThat(isV3EnvFile("path/to/foo.environment.yaml")).isTrue()
  }

  @Test
  fun testIsV3CollectionTrueForClasspathDirWithMarker() {
    assertThat(isV3Collection("pm-templates/v3/flat")).isTrue()
  }

  @Test
  fun testIsV3CollectionFalseForDirWithoutMarker() {
    assertThat(isV3Collection("pm-templates/v3/no-def")).isFalse()
  }

  @Test
  fun testIsV3CollectionFalseForV2JsonFile() {
    assertThat(isV3Collection("pm-templates/v2/steps-without-folders.postman_collection.json"))
      .isFalse()
  }

  @Test
  fun testIsV3CollectionFalseForMissingPath() {
    assertThat(isV3Collection("pm-templates/v3/does-not-exist")).isFalse()
    assertThat(isV3Collection("missing-classpath-resource")).isFalse()
  }

  @Test
  fun testBufferV3DefinitionReadsMarkerContent() {
    val content = bufferV3Definition("pm-templates/v3/flat").readUtf8()
    assertThat(content).contains("\$kind: collection")
  }

  @Test
  fun testBufferV3DefinitionThrowsWhenMarkerMissing() {
    org.junit.jupiter.api.Assertions.assertThrows(java.io.FileNotFoundException::class.java) {
      bufferV3Definition("pm-templates/v3/no-def").use { it.readUtf8() }
    }
  }

  @Test
  fun `readYamlMap parses a flat key-value yaml`() {
    val tmp = Files.createTempFile("config", ".yaml")
    Files.writeString(
      tmp,
      """
      baseUrl: https://localhost:6101
      username: admin@local.org
      password: secret
      apiVersion: 68.0
      """
        .trimIndent(),
    )
    val map = readYamlMap(tmp.toAbsolutePath().toString())
    assertThat(map)
      .containsExactly(
        "baseUrl",
        "https://localhost:6101",
        "username",
        "admin@local.org",
        "password",
        "secret",
        "apiVersion",
        68.0,
      )
  }

  @Test
  fun `readYamlMap returns empty for empty yaml`() {
    val tmp = Files.createTempFile("empty", ".yaml")
    assertThat(readYamlMap(tmp.toAbsolutePath().toString())).isEmpty()
  }
}
