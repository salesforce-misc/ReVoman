/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

  @Test
  fun `valid nested Flatted still decodes correctly`() {
    // Slot 0: array with refs to slots 1,2; Slot 1: obj with nested->slot3; Slot 2: obj with
    // value->slot4; Slot 3,4: strings
    val json = """[["1","2"],{"nested":"3"},{"value":"4"},"a","b"]"""
    val decoded = Flatted.parse(json) as List<*>
    decoded.size shouldBe 2
    val obj1 = decoded[0] as Map<*, *>
    val obj2 = decoded[1] as Map<*, *>
    obj1["nested"] shouldBe "a"
    obj2["value"] shouldBe "b"
  }

  @Test
  fun `non-numeric string reference throws clear error`() {
    // Array at slot 0 contains "not-a-number" which is not a valid index
    val json = """[["not-a-number"],"value"]"""
    val exception = shouldThrow<IllegalStateException> { Flatted.parse(json) }
    exception.message shouldContain "Flatted"
    exception.message shouldContain "not-a-number"
  }

  @Test
  fun `out-of-range index throws clear error`() {
    // Array at slot 0 contains "99" which is out of range (only 2 slots)
    val json = """[["99"],"value"]"""
    val exception = shouldThrow<IllegalStateException> { Flatted.parse(json) }
    exception.message shouldContain "Flatted"
    exception.message shouldContain "99"
  }
}
