/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.httpclient

import com.salesforce.revoman.input.readFileToString
import com.salesforce.revoman.input.readInputStreamToString
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStream

internal object HttpClientEnvironment {
  private val logger = KotlinLogging.logger {}
  private val moshi = Moshi.Builder().build()
  private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
  private val adapter = moshi.adapter<Map<String, Any?>>(mapType)

  internal fun isHttpClientEnvPath(path: String): Boolean {
    val lower = path.lowercase()
    return lower.endsWith(".env.json") && !lower.contains("postman_environment")
  }

  internal fun mergeEnvs(
    envPaths: Set<String>,
    envStreams: List<InputStream> = emptyList(),
    dynamicEnvironment: Map<String, Any?> = emptyMap(),
  ): Map<String, Any?> {
    val sources =
      envPaths
        .map { toSource(it) }
        .sortedBy { it.isPrivate }
        .mapNotNull { source -> parseEnv(source) } +
        envStreams.mapIndexedNotNull { index, stream ->
          parseEnv(EnvSource("env-stream-${index + 1}", readInputStreamToString(stream), null, false))
        }
    val merged =
      sources.fold(emptyMap<String, Any?>()) { acc, env -> acc + env.values }
    return merged + dynamicEnvironment
  }

  private fun toSource(rawPath: String): EnvSource {
    val (path, envName) = splitEnvName(rawPath)
    val lower = path.lowercase()
    val isPrivate = lower.contains("private.env")
    return EnvSource(path, readFileToString(path), envName, isPrivate)
  }

  private fun splitEnvName(rawPath: String): Pair<String, String?> {
    val parts = rawPath.split("#", limit = 2)
    return parts[0] to parts.getOrNull(1)?.trim().takeIf { !it.isNullOrBlank() }
  }

  private fun parseEnv(source: EnvSource): EnvSelection? {
    val root = runCatching { adapter.fromJson(source.content) }.getOrNull() ?: return null
    val isEnvMap = root.values.all { it is Map<*, *> }
    val resolvedEnv =
      if (isEnvMap) {
        val envName = source.envNameHint ?: root.keys.firstOrNull()
        if (envName == null) {
          logger.warn { "No environment name found in ${source.name}" }
          return null
        }
        val envValues = root[envName]
        if (envValues !is Map<*, *>) {
          logger.warn { "Environment '$envName' not found in ${source.name}" }
          return null
        }
        EnvSelection(envName, envValues.mapKeys { it.key.toString() })
      } else {
        EnvSelection(source.envNameHint ?: "default", root)
      }
    return resolvedEnv
  }

  private data class EnvSource(
    val name: String,
    val content: String,
    val envNameHint: String?,
    val isPrivate: Boolean,
  )

  private data class EnvSelection(val name: String, val values: Map<String, Any?>)
}
