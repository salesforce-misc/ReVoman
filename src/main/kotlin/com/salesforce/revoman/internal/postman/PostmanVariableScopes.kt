/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.output.postman.PostmanEnvironment

internal interface PostmanVariableScopes {
  @get:JvmSynthetic val environment: PostmanEnvironment<Any?>

  @get:JvmSynthetic val collectionVariables: PostmanEnvironment<Any?>

  @get:JvmSynthetic val globals: PostmanEnvironment<Any?>

  @get:JvmSynthetic @set:JvmSynthetic var environmentName: String?

  @JvmSynthetic fun contains(key: String): Boolean

  @JvmSynthetic fun resolve(key: String): Any?

  @JvmSynthetic fun ownerOf(key: String): PostmanEnvironment<Any?>?
}

@JvmSynthetic
internal fun postmanVariableScopes(
  environment: PostmanEnvironment<Any?>,
  collectionVariables: PostmanEnvironment<Any?>,
  globals: PostmanEnvironment<Any?>,
  environmentName: String?,
): PostmanVariableScopes =
  object : PostmanVariableScopes {
    @get:JvmSynthetic override val environment = environment

    @get:JvmSynthetic override val collectionVariables = collectionVariables

    @get:JvmSynthetic override val globals = globals

    @get:JvmSynthetic @set:JvmSynthetic override var environmentName = environmentName

    @JvmSynthetic
    override fun contains(key: String): Boolean =
      environment.containsKey(key) ||
        collectionVariables.containsKey(key) ||
        globals.containsKey(key)

    @JvmSynthetic override fun resolve(key: String): Any? = ownerOf(key)?.get(key)

    @JvmSynthetic
    override fun ownerOf(key: String): PostmanEnvironment<Any?>? =
      when {
        environment.containsKey(key) -> environment
        collectionVariables.containsKey(key) -> collectionVariables
        globals.containsKey(key) -> globals
        else -> null
      }
  }
