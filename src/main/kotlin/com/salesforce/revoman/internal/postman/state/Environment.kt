/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ****************************************************************************
 */
package com.salesforce.revoman.internal.postman.state

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true) internal data class Environment(val values: List<EnvValue>)

@JsonClass(generateAdapter = true)
internal data class EnvValue(val key: String, val value: String?, val enabled: Boolean)
