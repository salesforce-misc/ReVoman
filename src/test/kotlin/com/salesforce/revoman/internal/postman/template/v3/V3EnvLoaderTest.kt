/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class V3EnvLoaderTest {
  @Test
  fun testLoadEnvFromYamlFile() {
    val map = V3EnvLoader.loadFromPath("pm-templates/v3/test.environment.yaml")
    assertThat(map).containsEntry("baseUrl", "https://example.com")
    assertThat(map).containsEntry("count", "5")
    assertThat(map["emptyValue"]).isNull()
  }
}
