/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

class V3LoaderTest {
  @Test
  fun testLoadFlatCollectionOrdersByOrderField() {
    val dir = File("src/test/resources/pm-templates/v3/flat")
    val items = V3Loader.load(dir)
    assertThat(items).hasSize(3)
    val names = items.map { it.name }
    assertThat(names).containsExactly("b", "c", "a").inOrder()
  }

  @Test
  fun testLoadNestedCollectionWithAuthInheritanceAndOverride() {
    val dir = File("src/test/resources/pm-templates/v3/nested")
    val items = V3Loader.load(dir)
    assertThat(items).hasSize(2)
    assertThat(items[0].name).isEqualTo("inherits-auth")
    assertThat(items[0].request.auth!!.bearer.single().value).isEqualTo("OUTER")

    val sub = items[1]
    assertThat(sub.name).isEqualTo("sub")
    assertThat(sub.item).isNotNull()
    assertThat(sub.item).hasSize(1)
    val nested = sub.item!!.single()
    assertThat(nested.name).isEqualTo("overrides-auth")
    assertThat(nested.request.auth!!.bearer.single().value).isEqualTo("INNER")
  }
}
