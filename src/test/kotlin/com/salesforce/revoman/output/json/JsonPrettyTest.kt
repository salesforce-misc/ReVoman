/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.json

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class JsonPrettyTest {
  @Test
  fun `nests objects and arrays with two-space indent`() {
    assertThat(JsonPretty.pretty("""{"a":1,"b":[2,3]}"""))
      .isEqualTo("{\n  \"a\": 1,\n  \"b\": [\n    2,\n    3\n  ]\n}")
  }

  @Test
  fun `preserves structural chars inside a string literal`() {
    assertThat(JsonPretty.pretty("""{"q":"a,b:{c}"}"""))
      .isEqualTo("{\n  \"q\": \"a,b:{c}\"\n}")
  }

  @Test
  fun `respects escaped quote inside a string`() {
    assertThat(JsonPretty.pretty("""{"q":"he said \"hi\""}"""))
      .isEqualTo("{\n  \"q\": \"he said \\\"hi\\\"\"\n}")
  }

  @Test
  fun `preserves integer and large-id precision verbatim`() {
    assertThat(JsonPretty.pretty("""{"n":5,"id":1234567890123}"""))
      .isEqualTo("{\n  \"n\": 5,\n  \"id\": 1234567890123\n}")
  }

  @Test
  fun `returns non-json input unchanged`() {
    assertThat(JsonPretty.pretty("<html>not json</html>")).isEqualTo("<html>not json</html>")
    assertThat(JsonPretty.pretty("")).isEqualTo("")
    assertThat(JsonPretty.pretty("   ")).isEqualTo("   ")
  }

  @Test
  fun `empty object and array stay compact`() {
    assertThat(JsonPretty.pretty("""{"a":{},"b":[]}"""))
      .isEqualTo("{\n  \"a\": {},\n  \"b\": []\n}")
  }

  @Test
  fun `handles escaped backslash before closing quote`() {
    // value is  C:\  — the \\ is an escaped backslash, the next " is the real terminator.
    assertThat(JsonPretty.pretty("""{"path":"C:\\"}"""))
      .isEqualTo("{\n  \"path\": \"C:\\\\\"\n}")
  }

  @Test
  fun `preserves escape sequences as literal two-char sequences`() {
    // \n and \t inside the string must stay the two characters backslash-n / backslash-t,
    // NOT become a real newline/tab.
    assertThat(JsonPretty.pretty("""{"msg":"line1\nline2\ttab"}"""))
      .isEqualTo("{\n  \"msg\": \"line1\\nline2\\ttab\"\n}")
  }

  @Test
  fun `honors a custom indent`() {
    assertThat(JsonPretty.pretty("""{"a":1}""", "    "))
      .isEqualTo("{\n    \"a\": 1\n}")
  }
}
