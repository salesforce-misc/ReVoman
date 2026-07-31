/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.log

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BannerTest {
  private val emitted = mutableListOf<String>()

  @BeforeEach
  fun setUp() {
    Banner.resetForTest()
    emitted.clear()
    // Capture instead of routing to the real logger.
    Banner.emitForTest = { emitted += it }
  }

  @AfterEach
  fun tearDown() {
    Banner.resetForTest()
  }

  @Test
  fun `ctaText reports run and step counts with correct pluralization`() {
    Banner.ctaText(runs = 4, steps = 37) shouldContain "37 steps across 4 runs"
    Banner.ctaText(runs = 4, steps = 37) shouldContain "github.com/salesforce-misc/ReVoman"
    Banner.ctaText(runs = 1, steps = 1) shouldContain "1 step across 1 run"
  }

  @Test
  fun `bannerText carries wordmark, tagline and docs link`() {
    val text = Banner.bannerText()
    text shouldContain "ReṼoman"
    text shouldContain "API Orchestration Engine for the JVM"
    text shouldContain "sfdc.co/revoman-docs"
    text shouldContain "github.com/salesforce-misc/ReVoman"
  }

  @Test
  fun `banner prints exactly once across many runs`() {
    Banner.onRunStart()
    Banner.onRunStart()
    Banner.onRunStart()
    emitted.count { it.contains("API Orchestration Engine for the JVM") } shouldBe 1
  }

  @Test
  fun `bannerEnabled honors precedence and off-values`() {
    // property wins over env
    Banner.bannerEnabled(getProp = { "off" }, getEnv = { "on" }) shouldBe false
    Banner.bannerEnabled(getProp = { null }, getEnv = { "false" }) shouldBe false
    Banner.bannerEnabled(getProp = { null }, getEnv = { "0" }) shouldBe false
    Banner.bannerEnabled(getProp = { null }, getEnv = { "NO" }) shouldBe false
    // whitespace-padded values are trimmed and recognized
    Banner.bannerEnabled(getProp = { " off " }, getEnv = { null }) shouldBe false
    Banner.bannerEnabled(getProp = { null }, getEnv = { "  FALSE  " }) shouldBe false
    // default + non-off values enable
    Banner.bannerEnabled(getProp = { null }, getEnv = { null }) shouldBe true
    Banner.bannerEnabled(getProp = { null }, getEnv = { "anything" }) shouldBe true
  }

  @Test
  fun `counters accumulate across runs and steps`() {
    Banner.onRunStart()
    Banner.recordSteps(4)
    Banner.onRunStart()
    Banner.recordSteps(3)
    Banner.runCountForTest() shouldBe 2L
    Banner.stepCountForTest() shouldBe 7L
  }
}
