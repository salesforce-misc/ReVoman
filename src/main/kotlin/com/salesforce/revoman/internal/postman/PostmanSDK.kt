/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.runtime.LegacyRundownProgress

/** Transitional wiring facade retained only until CS4 removes the aggregate graph reference. */
internal interface PostmanSDK {
  @get:JvmSynthetic val scopes: PostmanVariableScopes

  @get:JvmSynthetic val capture: StepScriptCapture

  @get:JvmSynthetic val progress: LegacyRundownProgress

  @get:JvmSynthetic val regexReplacer: RegexReplacer
}

@JvmSynthetic
internal fun postmanSDK(
  scopes: PostmanVariableScopes,
  capture: StepScriptCapture,
  progress: LegacyRundownProgress,
  regexReplacer: RegexReplacer,
): PostmanSDK =
  object : PostmanSDK {
    @get:JvmSynthetic override val scopes = scopes

    @get:JvmSynthetic override val capture = capture

    @get:JvmSynthetic override val progress = progress

    @get:JvmSynthetic override val regexReplacer = regexReplacer
  }
