/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.input.config.CustomDynamicVariableGenerator
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Item
import com.salesforce.revoman.output.report.Step
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Exhaustive coverage of `{{key}}` resolution across the three persistent Postman scopes through
 * [RegexReplacer], honoring precedence (`environment` ▸ `collectionVariables` ▸ `globals`) and the
 * env-only side effects (warm-run `recordConsumed`, type-coercing write-back). Collection/global
 * hits resolve read-only — no ledger involvement, no store mutation.
 */
class RegexReplacerScopesTest {
  private val moshiReVoman = initMoshi()

  private fun pmWith(regexReplacer: RegexReplacer = RegexReplacer()): PostmanSDK {
    val pm = PostmanSDK(moshiReVoman, null, regexReplacer)
    pm.currentStepReport = mockk()
    pm.rundown = mockk()
    return pm
  }

  private fun replace(pm: PostmanSDK, s: String): String? =
    (pm.regexReplacer).replaceVariablesRecursively(s, pm)

  // ------------------------------------------------------------------ single-scope resolution

  @Test
  fun `resolves a key present only in environment`() {
    val pm = pmWith()
    pm.environment.set("k", "env")
    replace(pm, "v={{k}}") shouldBe "v=env"
  }

  @Test
  fun `resolves a key present only in collectionVariables`() {
    val pm = pmWith()
    pm.collectionVariables.set("k", "cv")
    replace(pm, "v={{k}}") shouldBe "v=cv"
  }

  @Test
  fun `resolves a key present only in globals`() {
    val pm = pmWith()
    pm.globals.set("k", "glob")
    replace(pm, "v={{k}}") shouldBe "v=glob"
  }

  // ------------------------------------------------------------------ precedence

  @Test
  fun `environment wins over collectionVariables`() {
    val pm = pmWith()
    pm.environment.set("k", "env")
    pm.collectionVariables.set("k", "cv")
    replace(pm, "v={{k}}") shouldBe "v=env"
  }

  @Test
  fun `environment wins over globals`() {
    val pm = pmWith()
    pm.environment.set("k", "env")
    pm.globals.set("k", "glob")
    replace(pm, "v={{k}}") shouldBe "v=env"
  }

  @Test
  fun `collectionVariables wins over globals`() {
    val pm = pmWith()
    pm.collectionVariables.set("k", "cv")
    pm.globals.set("k", "glob")
    replace(pm, "v={{k}}") shouldBe "v=cv"
  }

  @Test
  fun `environment wins when present in all three`() {
    val pm = pmWith()
    pm.environment.set("k", "env")
    pm.collectionVariables.set("k", "cv")
    pm.globals.set("k", "glob")
    replace(pm, "v={{k}}") shouldBe "v=env"
  }

  // ------------------------------------------------------------------ unknown key

  @Test
  fun `unknown key in all scopes is left as the literal double-brace token`() {
    val pm = pmWith()
    replace(pm, "v={{missing}}") shouldBe "v={{missing}}"
  }

  // ------------------------------------------------------------------ ledger guard (env-only)

  @Test
  fun `an environment hit is recorded as consumed`() {
    val pm = pmWith()
    pm.environment.currentStep = Step(index = "1", rawPMStep = Item(name = "s"))
    pm.environment.set("k", "env")
    replace(pm, "{{k}}") shouldBe "env"
    pm.environment.consumedKeysFor(pm.environment.currentStep) shouldContain "k"
  }

  @Test
  fun `a collectionVariables hit is NOT recorded as consumed`() {
    val pm = pmWith()
    pm.environment.currentStep = Step(index = "1", rawPMStep = Item(name = "s"))
    pm.collectionVariables.set("k", "cv")
    replace(pm, "{{k}}") shouldBe "cv"
    pm.environment.consumedKeysFor(pm.environment.currentStep) shouldNotContain "k"
  }

  @Test
  fun `a globals hit is NOT recorded as consumed`() {
    val pm = pmWith()
    pm.environment.currentStep = Step(index = "1", rawPMStep = Item(name = "s"))
    pm.globals.set("k", "glob")
    replace(pm, "{{k}}") shouldBe "glob"
    pm.environment.consumedKeysFor(pm.environment.currentStep) shouldNotContain "k"
  }

  // ------------------------------------------------------------------ setback guard (env-only)

  @Test
  fun `a collectionVariables hit does NOT leak into the environment store`() {
    val pm = pmWith()
    pm.collectionVariables.set("k", "cv")
    replace(pm, "{{k}}") shouldBe "cv"
    pm.environment.containsKey("k") shouldBe false
  }

  @Test
  fun `a globals hit does NOT leak into the environment store`() {
    val pm = pmWith()
    pm.globals.set("k", "glob")
    replace(pm, "{{k}}") shouldBe "glob"
    pm.environment.containsKey("k") shouldBe false
  }

  // ------------------------------------------------------------------ type coercion preserved
  // (env)

  @Test
  fun `environment numeric value resolves and is coerced back to Int on setback`() {
    val pm = pmWith()
    pm.environment.currentStep = Step(index = "1", rawPMStep = Item(name = "s"))
    pm.environment.set("n", 7)
    replace(pm, "{{n}}") shouldBe "7"
    // setback preserves the Int type (would become a String without the coercion path)
    pm.environment["n"] shouldBe 7
  }

  // ------------------------------------------------------------------ generators take priority

  @Test
  fun `custom dynamic variable takes priority over a scoped value of the same key`() {
    val custom = CustomDynamicVariableGenerator { _, _, _ -> "from-custom" }
    val noopDynamic = { _: String, _: PostmanSDK -> null }
    val rr = RegexReplacer(mapOf("k" to custom), noopDynamic)
    val pm = pmWith(rr)
    pm.environment.set("k", "env")
    pm.collectionVariables.set("k", "cv")
    pm.globals.set("k", "glob")
    replace(pm, "{{k}}") shouldBe "from-custom"
  }

  @Test
  fun `dynamic variable takes priority over a scoped value of the same key`() {
    val dynamic = { key: String, _: PostmanSDK -> if (key == "k") "from-dynamic" else null }
    val rr = RegexReplacer(emptyMap(), dynamic)
    val pm = pmWith(rr)
    pm.globals.set("k", "glob")
    replace(pm, "{{k}}") shouldBe "from-dynamic"
  }

  // ------------------------------------------------------------------ recursive resolution

  @Test
  fun `recursive resolution chains across mixed scopes`() {
    val pm = pmWith()
    pm.environment.set("a", "{{b}}")
    pm.collectionVariables.set("b", "{{c}}")
    pm.globals.set("c", "leaf")
    replace(pm, "{{a}}") shouldBe "leaf"
  }
}
