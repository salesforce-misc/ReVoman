/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template

import com.salesforce.revoman.internal.json.AlwaysSerializeNulls
import com.squareup.moshi.JsonClass
import org.http4k.format.ConfigurableMoshi

@JsonClass(generateAdapter = true)
internal data class Environment(val name: String?, val values: List<EnvValue>) {
  @AlwaysSerializeNulls
  @JsonClass(generateAdapter = true)
  internal data class EnvValue(val key: String, val value: String?, val enabled: Boolean)

  companion object {
    const val POSTMAN_ENV_NAME = "env-from-revoman"

    fun fromMap(envMap: Map<String, Any?>, moshiReVoman: ConfigurableMoshi): Environment {
      val values =
        envMap.entries.map { (key, value) ->
          val valueStr =
            when (value) {
              null,
              is String -> value
              value::class.javaPrimitiveType?.isPrimitive -> value.toString()
              else -> moshiReVoman.asFormatString(value)
            }
          EnvValue(key, valueStr, true)
        }
      return Environment(POSTMAN_ENV_NAME, values)
    }
  }
}
