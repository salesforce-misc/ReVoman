/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.template.v3

import com.salesforce.revoman.input.bufferFile
import io.github.oshai.kotlinlogging.KotlinLogging

/** V3 env read carrying both the display name and the flattened key→value map. */
internal data class V3EnvRead(val name: String?, val values: Map<String, Any?>)

internal object V3EnvLoader {
  fun loadFromPath(path: String): Map<String, Any?> = readWithName(path).values

  fun readWithName(path: String): V3EnvRead {
    val yaml = bufferFile(path).readUtf8()
    val env = V3YamlReader.readEnv(yaml)
    return V3EnvRead(env.name, env.values.associate { it.key to it.value })
  }
}

private val logger = KotlinLogging.logger {}
