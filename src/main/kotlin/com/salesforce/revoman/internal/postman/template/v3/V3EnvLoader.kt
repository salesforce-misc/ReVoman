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

internal object V3EnvLoader {
  fun loadFromPath(path: String): Map<String, Any?> {
    val yaml = bufferFile(path).readUtf8()
    val env = V3YamlReader.readEnv(yaml)
    return env.values.associate { it.key to it.value }
  }
}

private val logger = KotlinLogging.logger {}
