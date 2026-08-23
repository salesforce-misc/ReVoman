/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.output.postman.PostmanEnvironment

internal class PostmanVariableScopes(
  @get:JvmSynthetic val environment: PostmanEnvironment<Any?>,
  @get:JvmSynthetic val collectionVariables: PostmanEnvironment<Any?>,
  @get:JvmSynthetic val globals: PostmanEnvironment<Any?>,
  @get:JvmSynthetic @set:JvmSynthetic var environmentName: String?,
) {
  @JvmSynthetic
  fun ownerOf(key: String): PostmanEnvironment<Any?>? =
    when {
      environment.containsKey(key) -> environment
      collectionVariables.containsKey(key) -> collectionVariables
      globals.containsKey(key) -> globals
      else -> null
    }
}
