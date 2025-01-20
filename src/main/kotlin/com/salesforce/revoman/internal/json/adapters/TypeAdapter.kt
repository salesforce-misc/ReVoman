/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.adapters

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import java.lang.reflect.Type

object TypeAdapter {
  @ToJson fun toJson(type: Type): String = type.toString()

  @FromJson fun fromJson(ignore: String): Type? = null
}
