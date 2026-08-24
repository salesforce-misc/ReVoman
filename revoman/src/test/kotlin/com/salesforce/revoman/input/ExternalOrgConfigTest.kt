/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.jupiter.api.Test

class ExternalOrgConfigTest {
  @Test
  fun `readExternalOrgConfig reads creds from an explicit absolute path`() {
    val tmp = Files.createTempFile("external-org", ".yaml")
    Files.writeString(
      tmp,
      """
      baseUrl: https://localhost:6101
      username: admin@local.org
      password: secret
      """
        .trimIndent(),
    )
    val map = readExternalOrgConfig(tmp.toAbsolutePath().toString())
    assertThat(map)
      .containsExactly(
        "baseUrl",
        "https://localhost:6101",
        "username",
        "admin@local.org",
        "password",
        "secret",
      )
  }

  @Test
  fun `readExternalOrgConfig returns empty when the file is absent`() {
    val absent = Files.createTempDirectory("external-org").resolve("nope.yaml")
    assertThat(readExternalOrgConfig(absent.toAbsolutePath().toString())).isEmpty()
  }

  @Test
  fun `EXTERNAL_ORG_CONFIG_REL_PATH is the well-known dotfile path`() {
    assertThat(EXTERNAL_ORG_CONFIG_REL_PATH).isEqualTo(".revoman/config.yaml")
  }
}
