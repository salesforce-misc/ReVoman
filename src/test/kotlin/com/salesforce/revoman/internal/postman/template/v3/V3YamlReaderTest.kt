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

class V3YamlReaderTest {
  @Test
  fun testReadCollectionDefWithKindOnly() {
    val yaml =
      """
      ${'$'}kind: collection
      """
        .trimIndent()
    val def = V3YamlReader.readCollectionDef(yaml)
    assertThat(def.kind).isEqualTo("collection")
    assertThat(def.order).isNull()
    assertThat(def.auth).isEmpty()
  }
}
