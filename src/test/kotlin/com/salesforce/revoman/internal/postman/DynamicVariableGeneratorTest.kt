/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.internal.runtime.RundownProgress
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import kotlin.random.Random
import org.junit.jupiter.api.Test

class DynamicVariableGeneratorTest {
  @Test
  fun `currentRequestName reads only focused progress state`() {
    val environment = PostmanEnvironment<Any?>()
    val report =
      StepReport(
        step = Step(index = "request", rawPMStep = Item(name = "phase-one")),
        pmEnvSnapshot = environment,
      )
    val progress = RundownProgress()
    progress.begin(
      report,
      Rundown(
        stepReports = listOf(report),
        mutableEnv = environment,
        haltOnFailureOfTypeExcept = emptyMap(),
        providedStepsToExecuteCount = 1,
      ),
    )

    assertThat(dynamicVariableGenerator($$"$currentRequestName", progress)).isEqualTo("phase-one")
    assertThat(dynamicVariableGenerator("not-a-dynamic-variable", progress)).isNull()
  }

  @Test
  fun `getRandomHex always returns exactly 2 uppercase hex digits`() {
    repeat(100) {
      val hex = getRandomHex()
      assertThat(hex).hasLength(2)
      assertThat(hex).matches("[0-9A-F]{2}")
    }
  }

  @Test
  fun `getRandomHex covers the full 00 to FF range including the boundaries`() {
    // Deterministic: a seeded Random makes this prove — not gamble — that every byte value
    // 00..FF is reachable (the old 1000-draw `.contains("00")`/`.contains("FF")` tests were
    // flaky: P(a value never appears in N draws) = (255/256)^N, ~2% even at N=1000).
    val seededRandom = Random(42)
    val produced = (1..100_000).map { getRandomHex(seededRandom) }.toSet()
    val upperHex = java.util.HexFormat.of().withUpperCase()
    val allBytes = (0..255).map { upperHex.toHexDigits(it.toByte()) }.toSet()
    assertThat(produced).isEqualTo(allBytes)
    assertThat(produced).contains("00")
    assertThat(produced).contains("FF")
  }

  @Test
  fun `getRandomHex produces values in 00 to FF range`() {
    repeat(100) {
      val hex = getRandomHex()
      val value = hex.toInt(16)
      assertThat(value).isAtLeast(0)
      assertThat(value).isAtMost(255)
    }
  }

  @Test
  fun `getRandomHex output equals the legacy percent-02X formatting for all byte values`() {
    // Locks the exact rendering the old `"%02X".format(v)` produced across the full range.
    (0..255).forEach { v ->
      val legacy = "%02X".format(v)
      val formatted = java.util.HexFormat.of().withUpperCase().toHexDigits(v.toByte())
      assertThat(formatted).isEqualTo(legacy)
    }
  }

  @Test
  fun `randomAlphanumeric returns the requested length using only a-zA-Z0-9`() {
    listOf(0, 1, 15, 64).forEach { len ->
      val out = randomAlphanumeric(len)
      assertThat(out).hasLength(len)
      assertThat(out).matches("[a-zA-Z0-9]*")
    }
  }
}
