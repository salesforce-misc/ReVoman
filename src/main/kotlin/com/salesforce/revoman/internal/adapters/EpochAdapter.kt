package com.salesforce.revoman.internal.adapters

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import java.time.Instant
import java.util.Date

object EpochAdapter {
  @FromJson
  fun fromJson(reader: JsonReader, delegate: JsonAdapter<Date>): Date? {
    val epoch = reader.nextString()
    return if (epoch.matches("\\d+".toRegex())) {
      Date.from(Instant.ofEpochSecond(epoch.toLong()))
    } else {
      delegate.fromJson(epoch)
    }
  }
}
