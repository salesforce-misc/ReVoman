package org.revcloud.loki.common.json.adapters

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import java.time.Instant
import java.util.Date

object EpochAdapter {
  @ToJson fun toJson(date: Date): String = date.toString()

  @FromJson fun fromJson(epoch: String): Date = Date.from(Instant.ofEpochSecond(epoch.toLong()))
}
