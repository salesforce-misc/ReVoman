/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.adapters

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson

class ThrowableAdapter : JsonAdapter<Throwable>() {

  @FromJson
  override fun fromJson(reader: JsonReader): Throwable? {
    TODO()
  }

  @ToJson
  override fun toJson(writer: JsonWriter, value: Throwable?) {
    if (value == null) {
      writer.nullValue()
      return
    }
    writer.value(value.message)
  }
}
