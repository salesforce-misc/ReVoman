/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FlattedTest {
  @Test
  fun `decodes a flat array of event args`() {
    val decoded = Flatted.parse("""[["1","2"],"execute","spike1"]""")
    decoded shouldBe listOf("execute", "spike1")
  }

  @Test
  fun `decodes nested objects with deduped strings`() {
    val json = """[["1","2"],"assertion",{"name":"3","passed":true},"t"]"""
    val decoded = Flatted.parse(json) as List<*>
    decoded[0] shouldBe "assertion"
    val obj = decoded[1] as Map<*, *>
    obj["name"] shouldBe "t"
    obj["passed"] shouldBe true
  }

  @Test
  fun `decodes numbers and null`() {
    // Flatted.stringify(["x", 42, null]) => [["1",42,null],"x"]
    val decoded = Flatted.parse("""[["1",42,null],"x"]""") as List<*>
    decoded[0] shouldBe "x"
    (decoded[1] as Number).toInt() shouldBe 42
    decoded[2] shouldBe null
  }

  @Test
  fun `decodes circular references without infinite loop`() {
    val decoded = Flatted.parse("""[["1"],{"self":"1"}]""") as List<*>
    val obj = decoded[0] as Map<*, *>
    (obj["self"] === obj) shouldBe true
  }
}
