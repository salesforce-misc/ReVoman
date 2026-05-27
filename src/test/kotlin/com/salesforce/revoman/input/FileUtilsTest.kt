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
import org.junit.jupiter.api.Test

class FileUtilsTest {
  @Test
  fun `read file from resources to string`() {
    readFileToString("env-with-regex.json").shouldNotBeBlank()
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
}
