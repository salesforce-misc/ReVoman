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
}
