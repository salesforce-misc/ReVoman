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
import com.salesforce.revoman.input.isV3EnvFile
import com.salesforce.revoman.internal.json.AlwaysSerializeNulls
import com.salesforce.revoman.internal.json.MoshiReVoman
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import java.io.InputStream
import kotlin.collections.plus

/** The merged environment values plus the chosen environment display name (null if unnamed). */
internal data class MergedEnv(val name: String?, val values: Map<String, Any?>)

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
    ): MergedEnv {
      val envAdapter = Moshi.Builder().build().adapter<Environment>()

      // V3 yaml paths — read name + values from each.
      val yamlEnvs: List<com.salesforce.revoman.internal.postman.template.v3.V3EnvRead> =
        pmEnvironmentPaths
          .filter { isV3EnvFile(it) }
          .map { com.salesforce.revoman.internal.postman.template.v3.V3EnvLoader.readWithName(it) }
      val envFromYamlPaths: Map<String, Any?> =
        yamlEnvs.fold(emptyMap()) { acc, e -> acc + e.values }

      // V2 json paths — parse to Environment once, derive values + name.
      val jsonEnvs: List<Environment> =
        pmEnvironmentPaths
          .filterNot { isV3EnvFile(it) }
          .mapNotNull { envAdapter.fromJson(bufferFile(it)) }
      val envFromJsonPaths: Map<String, Any?> =
        jsonEnvs.flatMap { it.values.filter { v -> v.enabled } }.associate { it.key to it.value }

      // Streams — parse to Environment once (single read), derive values + name.
      val streamEnvs: List<Environment> = pmEnvironmentInputStreams.mapNotNull {
        envAdapter.fromJson(bufferInputStream(it))
      }
      val envFromStreams: Map<String, Any?> =
        streamEnvs.flatMap { it.values.filter { v -> v.enabled } }.associate { it.key to it.value }

      // * NOTE 10/09/23 gopala.akshintala: dynamicEnvironment keys replace envFromEnvFiles when
      // clashed
      // ! TODO 11 Jun 2025 gopala.akshintala: serialize only during regex replace
      val values = envFromYamlPaths + envFromJsonPaths + envFromStreams + dynamicEnvironment
      // Name precedence mirrors value order: last non-null wins (yaml -> json -> streams).
      val name =
        (yamlEnvs.map { it.name } + jsonEnvs.map { it.name } + streamEnvs.map { it.name })
          .lastOrNull { it != null }
      return MergedEnv(name, values)
    }
  }
}
