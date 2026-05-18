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

  @Test
  fun testReadRequestPostWithBodyAndMultiScript() {
    val yaml =
      """
      ${'$'}kind: http-request
      url: https://{{uri}}/objects
      method: POST
      body:
        type: json
        content: |-
          {
            "name": "x"
          }
      scripts:
        - type: afterResponse
          code: |-
            var responseJson = pm.response.json();
          language: text/javascript
        - type: beforeRequest
          code: |-
            var moment = require('moment')
          language: text/javascript
      order: 2000
      """
        .trimIndent()
    val req = V3YamlReader.readRequest(yaml)
    assertThat(req.method).isEqualTo("POST")
    assertThat(req.body).isNotNull()
    assertThat(req.body!!.type).isEqualTo("json")
    assertThat(req.body!!.content).contains("\"name\": \"x\"")
    assertThat(req.scripts).hasSize(2)
    assertThat(req.scripts[0].type).isEqualTo("afterResponse")
    assertThat(req.scripts[1].type).isEqualTo("beforeRequest")
  }

  @Test
  fun testYaml11BooleansCoercedToString() {
    val yaml =
      """
      ${'$'}kind: http-request
      url: "{{baseUrl}}/x"
      method: GET
      headers:
        preLog: yes
        onFlag: on
        offFlag: off
      """
        .trimIndent()
    val req = V3YamlReader.readRequest(yaml)
    assertThat(req.headers["preLog"]).isEqualTo("true")
    assertThat(req.headers["onFlag"]).isEqualTo("true")
    assertThat(req.headers["offFlag"]).isEqualTo("false")
  }

  @Test
  fun testReadEnv() {
    val yaml =
      """
      name: Pokemon
      values:
        - key: baseUrl
          value: 'https://pokeapi.co/api/v2'
        - key: enabled
          value: yes
      """
        .trimIndent()
    val env = V3YamlReader.readEnv(yaml)
    assertThat(env.name).isEqualTo("Pokemon")
    assertThat(env.values).hasSize(2)
    assertThat(env.values[0].key).isEqualTo("baseUrl")
    assertThat(env.values[0].value).isEqualTo("https://pokeapi.co/api/v2")
    assertThat(env.values[1].value).isEqualTo("true")
  }
}
