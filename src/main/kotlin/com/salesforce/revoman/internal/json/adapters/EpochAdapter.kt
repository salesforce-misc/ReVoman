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
import java.util.Date
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant

object EpochAdapter {
  @FromJson
  fun fromJson(reader: JsonReader, delegate: JsonAdapter<Date>): Date? {
    val epoch = reader.nextString()
    return if (epoch.matches("\\d+".toRegex())) {
      Date.from(Instant.fromEpochMilliseconds(epoch.toLong()).toJavaInstant())
    } else {
      delegate.fromJsonValue(epoch)
    }
  }
}
