/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json.factories

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse.Response.ErrorResponse
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse.Response.SuccessResponse
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import io.vavr.control.Either
import org.junit.jupiter.api.Test

class DiMorphicAdapterTest {
  private val moshi =
    initMoshi(
      customAdaptersWithType =
        mapOf(CompositeResponse.Response::class.java to Either.right(CompositeResponse.ADAPTER))
    )

  @Test
  fun `discriminates success and error across repeated elements in one list`() {
    val json =
      """
      {
        "compositeResponse": [
          {"referenceId":"ok","httpStatusCode":200,"httpHeaders":{},
           "body":{"done":true,"totalSize":0,"records":[]}},
          {"referenceId":"bad","httpStatusCode":400,"httpHeaders":{},
           "body":[{"errorCode":"INVALID","message":"Invalid reference specified"}]}
        ]
      }
      """
        .trimIndent()
    val response = moshi.fromJson<CompositeResponse>(json, CompositeResponse::class.java)!!
    assertThat(response.compositeResponse[0]).isInstanceOf(SuccessResponse::class.java)
    assertThat(response.compositeResponse[1]).isInstanceOf(ErrorResponse::class.java)
  }
}
