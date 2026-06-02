/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.postman.template.v3.V3Loader
import org.junit.jupiter.api.Test

class StepSourceHashTest {
  @Test
  fun `sourceHash defaults to empty when not provided`() {
    val step = Step(index = "1", rawPMStep = Item(name = "s"))
    assertThat(step.sourceHash).isEmpty()
  }

  @Test
  fun `sourceHash carries provided fingerprint`() {
    val step = Step(index = "1", rawPMStep = Item(name = "s"), sourceHash = "deadbeef")
    assertThat(step.sourceHash).isEqualTo("deadbeef")
  }

  @Test
  fun `v3-loaded items carry a non-empty sourceHash`() {
    // V3Loader.load returns List<Item>; the staleness fingerprint lands on Item.sourceHash.
    val items = V3Loader.load("pm-templates/v3/flat")
    assertThat(items).isNotEmpty()
    assertThat(items.all { it.sourceHash.isNotEmpty() }).isTrue()
  }

  @Test
  fun `Step built from a v3-loaded item carries the item sourceHash`() {
    // Mirror ReVoman.revUp: load -> deepFlattenItems -> Step. Verify the hash is carried forward.
    val steps = deepFlattenItems(V3Loader.load("pm-templates/v3/flat"))
    assertThat(steps).isNotEmpty()
    assertThat(steps.all { it.sourceHash.isNotEmpty() }).isTrue()
    // And each Step's hash matches its source Item's hash.
    assertThat(steps.all { it.sourceHash == it.rawPMStep.sourceHash }).isTrue()
  }
}
