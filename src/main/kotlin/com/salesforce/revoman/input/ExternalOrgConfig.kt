/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
@file:JvmName("ExternalOrgConfig")

package com.salesforce.revoman.input

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * The well-known external-org creds file, relative to the user's home dir. A human-friendly flat
 * `key: value` YAML (e.g. `baseUrl` / `username` / `password`) that lets a developer point a
 * ReVoman run at their own org WITHOUT committing creds — it lives in `$HOME`, outside any repo.
 */
const val EXTERNAL_ORG_CONFIG_REL_PATH: String = ".revoman/config.yaml"

/**
 * Read the well-known `~/.revoman/config.yaml` external-org creds into a plain map suitable for a
 * [com.salesforce.revoman.input.config.Kick] `dynamicEnvironment` overlay. Lenient: an absent file
 * returns an empty map so callers can overlay unconditionally (they decide whether missing creds
 * mean skip/fail). See [readExternalOrgConfig] (absolute-path overload) for the read semantics.
 */
fun readExternalOrgConfig(): Map<String, Any?> =
  readExternalOrgConfig(
    File(System.getProperty("user.home"), EXTERNAL_ORG_CONFIG_REL_PATH).absolutePath
  )

/**
 * Read a flat `key: value` external-org config at [absolutePath] into a plain map. Absent file →
 * empty map (logged). Malformed / non-mapping content reads as empty via [readYamlMap].
 */
fun readExternalOrgConfig(absolutePath: String): Map<String, Any?> =
  File(absolutePath)
    .takeIf { it.isFile }
    ?.let {
      readYamlMap(absolutePath).also { m ->
        logger.info { "External-org config loaded from $absolutePath: ${m.size} keys" }
      }
    }
    ?: emptyMap<String, Any?>().also {
      logger.info { "External-org config absent at $absolutePath — using empty overlay" }
    }

private val logger = KotlinLogging.logger {}
