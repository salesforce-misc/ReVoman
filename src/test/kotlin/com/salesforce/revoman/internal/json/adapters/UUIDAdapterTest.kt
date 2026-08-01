/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.adapters

import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UUIDAdapterTest {
  @Test
  fun `toJson renders a UUID as its canonical string`() {
    val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    assertThat(UUIDAdapter.toJson(uuid)).isEqualTo("123e4567-e89b-12d3-a456-426614174000")
  }

  @Test
  fun `fromJson round-trips a canonical UUID string`() {
    val uuid = UUID.randomUUID()
    assertThat(UUIDAdapter.fromJson(uuid.toString())).isEqualTo(uuid)
  }

  @Test
  fun `fromJson throws on a malformed UUID string`() {
    assertThrows<IllegalArgumentException> { UUIDAdapter.fromJson("not-a-uuid") }
  }
}
