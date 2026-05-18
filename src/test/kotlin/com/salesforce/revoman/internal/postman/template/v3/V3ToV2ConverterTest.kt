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

class V3ToV2ConverterTest {
  @Test
  fun testConvertBasicGetRequestUsesFilenameWhenNameAbsent() {
    val v3 = V3Request(url = "{{baseUrl}}/x", method = "GET")
    val item = V3ToV2Converter.toItem(v3, fallbackName = "x", inheritedAuth = null)
    assertThat(item.name).isEqualTo("x")
    assertThat(item.request.method).isEqualTo("GET")
    assertThat(item.request.url.raw).isEqualTo("{{baseUrl}}/x")
    assertThat(item.request.header).isEmpty()
    assertThat(item.request.body).isNull()
    assertThat(item.request.auth).isNull()
    assertThat(item.event).isNull()
  }

  @Test
  fun testConvertRequestNameOverridesFilename() {
    val v3 = V3Request(name = "explicit-name", url = "{{baseUrl}}/x", method = "GET")
    val item = V3ToV2Converter.toItem(v3, fallbackName = "filename-fallback", inheritedAuth = null)
    assertThat(item.name).isEqualTo("explicit-name")
  }
}
