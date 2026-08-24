/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.junit.jupiter.api.Test

class RequestCoordinatesTest {

  @Test
  fun `extracts method authority and path from a request`() {
    val req = Request(Method.GET, "https://pokeapi.co/api/v2/pokemon/ditto?x=1")
    val (method, host, path) = requestCoordinates(req)
    method shouldBe "GET"
    host shouldBe "pokeapi.co"
    path shouldBe "/api/v2/pokemon/ditto"
  }

  @Test
  fun `authority keeps an explicit port`() {
    val req = Request(Method.POST, "https://localhost:6101/services/data")
    val (_, host, _) = requestCoordinates(req)
    host shouldBe "localhost:6101"
  }
}
