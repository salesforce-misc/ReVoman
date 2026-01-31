/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template

import com.salesforce.revoman.input.readFileToString
import com.salesforce.revoman.input.readInputStreamToString
import com.salesforce.revoman.internal.json.AlwaysSerializeNulls
import com.salesforce.revoman.internal.json.MoshiReVoman
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.adapter
import java.io.InputStream
import kotlin.collections.plus
import io.github.oshai.kotlinlogging.KotlinLogging

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
      inlineVariables: Map<String, Any?> = emptyMap(),
      httpEnvironmentName: String? = null,
    ): Map<String, Any?> {
      val envFromEnvFiles =
        (pmEnvironmentPaths.map { readFileToString(it) } +
            pmEnvironmentInputStreams.map { readInputStreamToString(it) })
          .flatMap { content -> parseEnvironment(content, httpEnvironmentName).entries }
          .associate { it.key to it.value }
      // * NOTE 10/09/23 gopala.akshintala: dynamicEnvironment keys replace envFromEnvFiles when
      // clashed
      // ! TODO 11 Jun 2025 gopala.akshintala: serialize only during regex replace
      return envFromEnvFiles + inlineVariables + dynamicEnvironment
    }

    private fun parseEnvironment(
      content: String,
      httpEnvironmentName: String?,
    ): Map<String, Any?> =
      parsePostmanEnvironment(content)
        ?: parseJetBrainsEnvironment(content, httpEnvironmentName)
        ?: emptyMap()

    @OptIn(ExperimentalStdlibApi::class)
    private fun parsePostmanEnvironment(content: String): Map<String, Any?>? {
      val envAdapter = Moshi.Builder().build().adapter<Environment>()
      val env =
        runCatching { envAdapter.fromJson(content) }
          .onFailure { logger.warn(it) { "Failed to parse Postman environment" } }
          .getOrNull()
          ?: return null
      return env.values.filter { it.enabled }.associate { it.key to it.value }
    }

    private fun parseJetBrainsEnvironment(
      content: String,
      httpEnvironmentName: String?,
    ): Map<String, Any?>? {
      val moshi = Moshi.Builder().build()
      val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
      val adapter = moshi.adapter<Map<String, Any?>>(mapType)
      val rawMap =
        runCatching { adapter.fromJson(content) }
          .onFailure { logger.warn(it) { "Failed to parse JetBrains environment" } }
          .getOrNull()
          ?: return null
      return extractJetBrainsEnv(rawMap, httpEnvironmentName)
    }

    private fun extractJetBrainsEnv(
      rawMap: Map<String, Any?>,
      httpEnvironmentName: String?,
    ): Map<String, Any?> {
      if (rawMap.isEmpty()) return emptyMap()
      val nestedEnv = rawMap.values.all { it is Map<*, *> }
      if (!nestedEnv) return rawMap
      val envKey =
        when {
          !httpEnvironmentName.isNullOrBlank() -> httpEnvironmentName
          rawMap.size == 1 -> rawMap.keys.first()
          else -> null
        }
      if (envKey == null) {
        logger.warn { "Multiple JetBrains environments found but no environment name was provided" }
        return emptyMap()
      }
      val envValue = rawMap[envKey] as? Map<*, *> ?: emptyMap<Any, Any?>()
      if (envValue.isEmpty()) {
        logger.warn { "JetBrains environment '$envKey' was not found or empty" }
      }
      return envValue.entries.associate { it.key.toString() to it.value }
    }
  }
}

private val logger = KotlinLogging.logger {}
