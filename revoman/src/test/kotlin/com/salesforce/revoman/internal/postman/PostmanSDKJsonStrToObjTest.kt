/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Test

/**
 * A3 guard: `jsonStrToObj` memoizes the JSON.parse arrow CLOSURE, not the parse RESULT. Repeated
 * calls with different inputs must still parse each input correctly (closure reuse != result
 * cache).
 */
class PostmanSDKJsonStrToObjTest {
  private val pm = PostmanSDK(initMoshi())

  @Test
  fun `repeated parses with different inputs each parse correctly`() {
    pm.jsonStrToObj("""{"a": 1}""").getMember("a").asInt() shouldBe 1
    pm.jsonStrToObj("""{"a": 2}""").getMember("a").asInt() shouldBe 2
    pm.jsonStrToObj("""{"b": "x"}""").getMember("b").asString() shouldBe "x"
  }

  @Test
  fun `raw JSON5 comments are not tolerated by jsonStrToObj (stripped upstream in Template)`() {
    // Pins the REAL contract: `jsonStrToObj` does NOT tolerate JSON5 comments. Its closure is
    // `JSON.parse(jsonStr, {allowComments: true})`, but per the ECMAScript spec `JSON.parse`'s
    // 2nd argument is the *reviver* position — a non-callable `{allowComments: true}` is a
    // silent no-op, so standard `JSON.parse` runs and rejects `//` comments with a SyntaxError.
    // This is not a bug: comment-bearing request bodies have their JSON5 comments stripped
    // UPSTREAM in `Template.toHttpRequest` (via the Moshi round-trip) before ever reaching
    // `jsonStrToObj`, so in production this method only ever sees comment-free JSON.
    shouldThrow<PolyglotException> {
      pm.jsonStrToObj(
        """
        {
          // a line comment
          "a": 1
        }
        """
          .trimIndent()
      )
    }
  }
}
