/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json.adapters.salesforce

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.squareup.moshi.JsonDataException
import io.vavr.control.Either
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CompositeResponseTest {
  private val moshi =
    initMoshi(
      customAdaptersWithType =
        mapOf(CompositeResponse.Response::class.java to Either.right(CompositeResponse.ADAPTER))
    )

  @Test
  fun `Record parsing throws JsonDataException when attributes field is missing`() {
    val json =
      """
      {
          "compositeResponse": [{
              "referenceId": "ref1",
              "httpStatusCode": 200,
              "httpHeaders": {},
              "body": {
                  "done": true,
                  "totalSize": 1,
                  "records": [{
                      "someField": "someValue",
                      "anotherId": "12345"
                  }]
              }
          }]
      }
      """
        .trimIndent()

    val exception =
      assertThrows<JsonDataException> {
        moshi.fromJson<CompositeResponse>(json, CompositeResponse::class.java)
      }

    assertThat(exception.message).contains("Record missing required 'attributes' field")
  }

  @Test
  fun `Record parsing succeeds when attributes field is present`() {
    val json =
      """
      {
          "compositeResponse": [{
              "referenceId": "ref1",
              "httpStatusCode": 200,
              "httpHeaders": {},
              "body": {
                  "done": true,
                  "totalSize": 1,
                  "records": [{
                      "attributes": {
                          "type": "Account",
                          "url": "/services/data/v58.0/sobjects/Account/001xx000003DGbXXXX"
                      },
                      "Id": "001xx000003DGbXXXX",
                      "Name": "Test Account"
                  }]
              }
          }]
      }
      """
        .trimIndent()

    val response = moshi.fromJson<CompositeResponse>(json, CompositeResponse::class.java)!!

    assertThat(response.compositeResponse).hasSize(1)
    val successResponse =
      response.compositeResponse.first() as CompositeResponse.Response.SuccessResponse
    assertThat(successResponse.body?.records).hasSize(1)

    val record = successResponse.body?.records?.first()!!
    assertThat(record.attributes.type).isEqualTo("Account")
    assertThat(record.attributes.url)
      .isEqualTo("/services/data/v58.0/sobjects/Account/001xx000003DGbXXXX")
    assertThat(record.recordBody["Id"]).isEqualTo("001xx000003DGbXXXX")
    assertThat(record.recordBody["Name"]).isEqualTo("Test Account")
  }
}
