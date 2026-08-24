/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.factories

import com.salesforce.revoman.internal.json.AlwaysSerializeNulls
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.rawType
import java.lang.reflect.Type

internal class AlwaysSerializeNullsFactory : JsonAdapter.Factory {
  override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
    val rawType: Class<*> = type.rawType
    if (!rawType.isAnnotationPresent(AlwaysSerializeNulls::class.java)) {
      return null
    }
    val delegate: JsonAdapter<Any> = moshi.nextAdapter(this, type, annotations)
    return delegate.serializeNulls()
  }
}
