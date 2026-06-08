/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import com.google.common.truth.Truth.assertThat
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.junit.jupiter.api.Test

class RenderHttpMsgTest {
  @Test
  fun `normalizes CRLF and pretty-prints a json body`() {
    val req =
      Request(Method.POST, "https://localhost:6101/x")
        .header("Content-Type", "application/json")
        .body("""{"a":1,"b":[2,3]}""")
    val rendered = renderHttpMsg(req)
    assertThat(rendered).doesNotContain("\r")
    assertThat(rendered).contains("POST https://localhost:6101/x")
    assertThat(rendered).contains("Content-Type: application/json")
    assertThat(rendered).contains("\"a\": 1")
    assertThat(rendered).contains("    2,")
  }

  @Test
  fun `leaves a non-json body verbatim`() {
    val res = Response(Status.OK).body("plain text, not json")
    val rendered = renderHttpMsg(res)
    assertThat(rendered).contains("plain text, not json")
  }

  @Test
  fun `handles a message with no body`() {
    val req = Request(Method.GET, "https://localhost:6101/ping")
    val rendered = renderHttpMsg(req)
    assertThat(rendered).doesNotContain("\r")
    assertThat(rendered).contains("GET https://localhost:6101/ping")
  }
}
