/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.adapters

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson
import java.math.BigDecimal

object BigDecimalAdapter {
  // Write the BigDecimal as an EXACT JSON number. JsonWriter.value(Number) emits the Number's
  // toString() verbatim as a bare JSON number — no Double hop, so full precision is preserved
  // (the old `value.toDouble()` rounded, e.g. 5 -> 5.0 and large/high-scale values to E-notation).
  @ToJson
  fun toJson(writer: JsonWriter, value: BigDecimal) {
    writer.value(value as Number)
  }

  // Read side is already exact: the number token is consumed as its literal string and
  // reconstructed.
  @FromJson fun fromJson(string: String): BigDecimal = BigDecimal(string)
}
