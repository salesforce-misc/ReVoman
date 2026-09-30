/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.graph

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GraphResourcesTest {
  @Test
  fun `each graph directory has a V3 definition on the classpath`() {
    listOf("configure", "price", "quote").forEach { graph ->
      val def = javaClass.classLoader.getResource("graphs/$graph/.resources/definition.yaml")
      assertThat(def).isNotNull()
    }
  }

  @Test
  fun `price request references the configId placeholder threaded from configure`() {
    val priceReq =
      javaClass.classLoader.getResource("graphs/price/price-config.request.yaml")!!.readText()
    assertThat(priceReq).contains("{{configId}}")
  }
}
