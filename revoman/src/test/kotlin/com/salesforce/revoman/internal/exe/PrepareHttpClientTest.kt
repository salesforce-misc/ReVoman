/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PrepareHttpClientTest {
  @Test
  fun `secure variant returns the same memoized handler across calls`() {
    assertThat(prepareHttpClient(false)).isSameInstanceAs(prepareHttpClient(false))
  }

  @Test
  fun `insecure variant returns the same memoized handler across calls`() {
    assertThat(prepareHttpClient(true)).isSameInstanceAs(prepareHttpClient(true))
  }

  @Test
  fun `secure and insecure are distinct handlers`() {
    assertThat(prepareHttpClient(false)).isNotSameInstanceAs(prepareHttpClient(true))
  }
}
