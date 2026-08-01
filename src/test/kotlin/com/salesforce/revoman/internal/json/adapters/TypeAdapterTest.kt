/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.adapters

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TypeAdapterTest {
  @Test
  fun `toJson renders a Type as its toString`() {
    assertThat(TypeAdapter.toJson(String::class.java)).isEqualTo("class java.lang.String")
  }

  @Test
  fun `fromJson always returns null (types are never deserialized)`() {
    assertThat(TypeAdapter.fromJson("anything")).isNull()
  }
}
