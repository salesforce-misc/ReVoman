package com.salesforce.revoman.internal.adapters

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import java.math.BigDecimal

object BigDecimalAdapter {
  @ToJson fun toJson(value: BigDecimal) = value.toDouble()

  @FromJson fun fromJson(string: String) = BigDecimal(string)
}
