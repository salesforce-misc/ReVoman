/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import org.junit.jupiter.api.Test

class ModuleWiringTest {
  @Test
  fun `ReVoman engine is on the harness module classpath`() {
    // ReVoman is a Kotlin `object` (singleton); referencing it proves the project dep resolves.
    assertThat(ReVoman.toString()).isNotEmpty()
  }
}
