/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template

import com.salesforce.revoman.input.bufferFile
import com.salesforce.revoman.input.bufferInputStream
import com.salesforce.revoman.internal.json.AlwaysSerializeNulls
import com.salesforce.revoman.internal.json.MoshiReVoman
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import java.io.InputStream
import kotlin.collections.plus

@JsonClass(generateAdapter = true)
internal data class Environment(val name: String?, val values: List<EnvValue>) {
  @AlwaysSerializeNulls
  @JsonClass(generateAdapter = true)
  internal data class EnvValue(val key: String, val value: String?, val enabled: Boolean)

  companion object {
    const val POSTMAN_ENV_NAME = "env-from-revoman"

    fun fromMap(envMap: Map<String, Any?>, moshiReVoman: MoshiReVoman): Environment {
      val values =
        envMap.entries.map { (key, value) -> EnvValue(key, moshiReVoman.anyToString(value), true) }
      return Environment(POSTMAN_ENV_NAME, values)
    }

    @OptIn(ExperimentalStdlibApi::class)
    internal fun mergeEnvs(
      pmEnvironmentPaths: Set<String>,
      pmEnvironmentInputStreams: List<InputStream>,
      dynamicEnvironment: Map<String, Any?>,
    ): Map<String, Any?> {
      val envAdapter = Moshi.Builder().build().adapter<Environment>()
      val envFromEnvFiles =
        (pmEnvironmentPaths.map { bufferFile(it) } +
            pmEnvironmentInputStreams.map { bufferInputStream(it) })
          .flatMap { envWithRegex ->
            envAdapter.fromJson(envWithRegex)?.values?.filter { it.enabled } ?: emptyList()
          }
          .associate { it.key to it.value }
      // * NOTE 10/09/23 gopala.akshintala: dynamicEnvironment keys replace envFromEnvFiles when
      // clashed
      // ! TODO 11 Jun 2025 gopala.akshintala: serialize only during regex replace
      return envFromEnvFiles + dynamicEnvironment
    }
  }
}
