/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Cs2aManifestValidatorTest {
  @TempDir lateinit var temporaryDirectory: Path

  @Test
  fun `path-free manifest validator accepts remote paths through shebang and jq file modes`() {
    val fixture = writeFixture("valid.json", VALID_MANIFEST)

    assertThat(Files.isExecutable(validator)).isTrue()
    assertValidationSucceeds(listOf(validator.toString(), fixture.toString()))
    assertValidationSucceeds(listOf("jq", "-e", "-f", validator.toString(), fixture.toString()))
  }

  @Test
  fun `manifest validator rejects missing and extra keys at every object level`() {
    val mutations =
      REQUIRED_TOP_LEVEL_KEYS.map { "del(.$it)" } +
        REQUIRED_JDK_KEYS.map { "del(.jdk.$it)" } +
        REQUIRED_ARTIFACT_KEYS.map { "del(.classpath[0].$it)" } +
        listOf(
          ".unexpected = true",
          ".jdk.unexpected = true",
          ".classpath[0].unexpected = true",
        )

    mutations.forEachIndexed { index, filter -> assertMutationRejected(index, filter) }
  }

  @Test
  fun `manifest validator rejects every wrong JSON type for every field`() {
    var index = 0
    FIELD_TYPES.forEach { (path, acceptedType) ->
      WRONG_VALUES.filterKeys { it != acceptedType }
        .values
        .forEach { wrongValue ->
          assertMutationRejected(index++, "$path = $wrongValue")
        }
    }
  }

  @Test
  fun `manifest validator rejects blank patterns numeric bounds arrays and duplicate IDs`() {
    val mutations =
      NONBLANK_PATHS.flatMap { path ->
        listOf("$path = \"\"", "$path = \" \\t\"")
      } +
        HASH_40_PATHS.flatMap(::badHashes) +
        HASH_64_PATHS.flatMap(::badHashes) +
        listOf(
          ".classpath[0].sizeBytes = -1",
          ".classpath[0].sizeBytes = 1.5",
          ".jdk.jvmFlags = []",
          ".jdk.jvmFlags = [] | .jdk.jvmFlags[0] = 1",
          ".jdk.jvmFlags = [\"\"]",
          ".jdk.jvmFlags = [\" \\t\"]",
          ".classpath = []",
          ".classpath += [.classpath[0] | .executionPath = \"/remote/other.jar\"]",
        )

    mutations.forEachIndexed { index, filter -> assertMutationRejected(index, filter) }
  }

  private fun badHashes(path: String): List<String> {
    val length = if (path in HASH_40_PATHS) 40 else 64
    return listOf(
      "$path = \"${"a".repeat(length - 1)}\"",
      "$path = \"${"A".repeat(length)}\"",
      "$path = \"${"g".repeat(length)}\"",
    )
  }

  private fun assertMutationRejected(index: Int, filter: String) {
    val fixture = writeFixture("invalid-$index.json", mutate(filter))
    val result = run(listOf("jq", "-e", "-f", validator.toString(), fixture.toString()))
    assertWithMessage("mutation must be rejected: $filter\n${result.output}")
      .that(result.exitCode)
      .isNotEqualTo(0)
  }

  private fun mutate(filter: String): String {
    val base = writeFixture("mutation-base.json", VALID_MANIFEST)
    val result = run(listOf("jq", "-c", filter, base.toString()))
    assertWithMessage("fixture mutation failed: $filter\n${result.output}")
      .that(result.exitCode)
      .isEqualTo(0)
    return result.output
  }

  private fun assertValidationSucceeds(command: List<String>) {
    val result = run(command)
    assertWithMessage("validator output:\n${result.output}").that(result.exitCode).isEqualTo(0)
  }

  private fun run(command: List<String>): ProcessResult {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return ProcessResult(process.waitFor(), output)
  }

  private fun writeFixture(name: String, content: String): Path =
    temporaryDirectory.resolve(name).also { Files.writeString(it, content) }

  private val validator: Path =
    Path.of("docs/superpowers/benchmarks/operators/cs2a-validate-manifest.jq")
      .toAbsolutePath()
      .normalize()

  private data class ProcessResult(val exitCode: Int, val output: String)

  private companion object {
    val REQUIRED_TOP_LEVEL_KEYS =
      listOf(
        "schema",
        "targetId",
        "gitCommit",
        "gitTree",
        "dirty",
        "gradleVersion",
        "wrapperSha256",
        "jdk",
        "classpath",
      )
    val REQUIRED_JDK_KEYS = listOf("distribution", "vendor", "fullVersion", "javaHome", "jvmFlags")
    val REQUIRED_ARTIFACT_KEYS = listOf("logicalId", "executionPath", "sizeBytes", "sha256")
    val WRONG_VALUES =
      linkedMapOf(
        "string" to "\"wrong\"",
        "boolean" to "true",
        "number" to "1",
        "object" to "{}",
        "array" to "[]",
        "null" to "null",
      )
    val FIELD_TYPES =
      linkedMapOf(
        ".schema" to "string",
        ".targetId" to "string",
        ".gitCommit" to "string",
        ".gitTree" to "string",
        ".dirty" to "boolean",
        ".gradleVersion" to "string",
        ".wrapperSha256" to "string",
        ".jdk" to "object",
        ".jdk.distribution" to "string",
        ".jdk.vendor" to "string",
        ".jdk.fullVersion" to "string",
        ".jdk.javaHome" to "string",
        ".jdk.jvmFlags" to "array",
        ".jdk.jvmFlags[0]" to "string",
        ".classpath" to "array",
        ".classpath[0]" to "object",
        ".classpath[0].logicalId" to "string",
        ".classpath[0].executionPath" to "string",
        ".classpath[0].sizeBytes" to "number",
        ".classpath[0].sha256" to "string",
      )
    val NONBLANK_PATHS =
      listOf(
        ".targetId",
        ".gradleVersion",
        ".jdk.distribution",
        ".jdk.vendor",
        ".jdk.fullVersion",
        ".jdk.javaHome",
        ".classpath[0].logicalId",
        ".classpath[0].executionPath",
      )
    val HASH_40_PATHS = listOf(".gitCommit", ".gitTree")
    val HASH_64_PATHS = listOf(".wrapperSha256", ".classpath[0].sha256")
    val VALID_MANIFEST =
      """
      {
        "schema": "revoman-target-manifest/v1",
        "targetId": "remote-target",
        "gitCommit": "${"a".repeat(40)}",
        "gitTree": "${"b".repeat(40)}",
        "dirty": false,
        "gradleVersion": "9.7.0",
        "wrapperSha256": "${"c".repeat(64)}",
        "jdk": {
          "distribution": "remote-jdk",
          "vendor": "Remote Vendor",
          "fullVersion": "21.0.10+7",
          "javaHome": "/nonexistent/remote/jdk",
          "jvmFlags": ["-Xms256m"]
        },
        "classpath": [{
          "logicalId": "revoman-root",
          "executionPath": "/nonexistent/remote/revoman.jar",
          "sizeBytes": 123,
          "sha256": "${"d".repeat(64)}"
        }]
      }
      """
        .trimIndent()
  }
}
