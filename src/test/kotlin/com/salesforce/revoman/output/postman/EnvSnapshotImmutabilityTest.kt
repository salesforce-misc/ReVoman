/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.postman

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EnvSnapshotImmutabilityTest {
  @Test
  fun `snapshot is a point-in-time view - later env writes do not change it`() {
    val env = PostmanEnvironment<Any?>(PersistentBackedMutableMap(mapOf("a" to 1)))
    val snapshot = env.o1Snapshot()
    env["b"] = 2 // mutate live env AFTER the snapshot (regex write-back path)
    env.putAll(mapOf("c" to 3))
    assertThat(snapshot.mutableEnv).containsExactlyEntriesIn(mapOf("a" to 1))
    assertThat(env.mutableEnv).containsExactlyEntriesIn(mapOf("a" to 1, "b" to 2, "c" to 3))
  }

  @Test
  fun `o1Snapshot preserves value including nulls`() {
    val env = PostmanEnvironment<Any?>(PersistentBackedMutableMap(mapOf("n" to null)))
    val snapshot = env.o1Snapshot()
    assertThat(snapshot.containsKey("n")).isTrue()
    assertThat(snapshot["n"]).isNull()
  }
}
