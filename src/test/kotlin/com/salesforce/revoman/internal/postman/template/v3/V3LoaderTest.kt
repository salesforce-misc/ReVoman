/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class V3LoaderTest {
  @Test
  fun testLoadFlatCollectionOrdersByOrderField() {
    val items = V3Loader.load("pm-templates/v3/flat")
    assertThat(items).hasSize(3)
    val names = items.map { it.name }
    assertThat(names).containsExactly("b", "c", "a").inOrder()
  }

  @Test
  fun testLoadNestedCollectionWithAuthInheritanceAndOverride() {
    val items = V3Loader.load("pm-templates/v3/nested")
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

  @Test
  fun testLoadThrowsWhenDefinitionMissing() {
    val ex =
      assertThrows(IllegalStateException::class.java) { V3Loader.load("pm-templates/v3/no-def") }
    assertThat(ex.message).contains("Not a v3 collection root")
    assertThat(ex.message).contains(".resources/definition.yaml")
  }

  @Test
  fun testLoadHandlesBracketsAndSpacesInPath() {
    val items = V3Loader.load("pm-templates/v3/with [brackets]")
    assertThat(items).hasSize(1)
    assertThat(items[0].name).isEqualTo("req")
  }

  @Test
  fun testLoadMixedBodies() {
    val items = V3Loader.load("pm-templates/v3/mixed-bodies")
    assertThat(items).hasSize(2)
    val post = items[0]
    assertThat(post.name).isEqualTo("post-json")
    assertThat(post.request.method).isEqualTo("POST")
    assertThat(post.request.body!!.raw).contains("\"a\":1")
    assertThat(post.event).isNotNull()
    assertThat(post.event!!.map { it.listen }.toSet()).containsExactly("test", "prerequest")
    val patch = items[1]
    assertThat(patch.request.method).isEqualTo("PATCH")
    assertThat(patch.request.body!!.raw).isEqualTo("hello")
  }
}
