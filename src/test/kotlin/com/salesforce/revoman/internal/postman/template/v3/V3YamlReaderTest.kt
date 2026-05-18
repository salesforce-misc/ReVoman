/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class V3YamlReaderTest {
  @Test
  fun testReadCollectionDefWithKindOnly() {
    val yaml =
      """
      ${'$'}kind: collection
      """
        .trimIndent()
    val def = V3YamlReader.readCollectionDef(yaml)
    assertThat(def.kind).isEqualTo("collection")
    assertThat(def.order).isNull()
    assertThat(def.auth).isEmpty()
  }

  @Test
  fun testReadCollectionDefWithAuthAndOrder() {
    val yaml =
      """
      ${'$'}kind: collection
      order: 1000
      auth:
        - id: 88daae21-effd-4cd0-b24a-65bc7a382e35
          type: bearer
          name: bearer auth
          credentials:
            token: "{{accessToken}}"
      """
        .trimIndent()
    val def = V3YamlReader.readCollectionDef(yaml)
    assertThat(def.kind).isEqualTo("collection")
    assertThat(def.order).isEqualTo(1000)
    assertThat(def.auth).hasSize(1)
    val auth = def.auth.single()
    assertThat(auth.id).isEqualTo("88daae21-effd-4cd0-b24a-65bc7a382e35")
    assertThat(auth.type).isEqualTo("bearer")
    assertThat(auth.name).isEqualTo("bearer auth")
    assertThat(auth.credentials).containsEntry("token", "{{accessToken}}")
  }

  @Test
  fun testReadRequestBasicGet() {
    val yaml =
      """
      ${'$'}kind: http-request
      url: "{{baseUrl}}/nature/{{id}}"
      method: GET
      headers:
        preLog: "true"
      order: 3000
      """
        .trimIndent()
    val req = V3YamlReader.readRequest(yaml)
    assertThat(req.kind).isEqualTo("http-request")
    assertThat(req.url).isEqualTo("{{baseUrl}}/nature/{{id}}")
    assertThat(req.method).isEqualTo("GET")
    assertThat(req.headers).containsEntry("preLog", "true")
    assertThat(req.order).isEqualTo(3000)
    assertThat(req.body).isNull()
    assertThat(req.scripts).isEmpty()
    assertThat(req.auth).isEmpty()
  }
}
