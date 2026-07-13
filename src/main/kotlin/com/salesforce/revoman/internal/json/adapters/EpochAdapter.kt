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
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant

object EpochAdapter {
  // * NOTE: hoisted so the digit pattern is compiled once, not on every date parse.
  //   `\d` keeps ASCII-only semantics identical to the previous inline regex.
  private val DIGITS_REGEX = "\\d+".toRegex()

  @OptIn(ExperimentalTime::class)
  @FromJson
  fun fromJson(reader: JsonReader, delegate: JsonAdapter<Date>): Date? {
    val epoch = reader.nextString()
    return if (epoch.matches(DIGITS_REGEX)) {
      Date.from(Instant.fromEpochMilliseconds(epoch.toLong()).toJavaInstant())
    } else {
      delegate.fromJsonValue(epoch)
    }
  }
}
