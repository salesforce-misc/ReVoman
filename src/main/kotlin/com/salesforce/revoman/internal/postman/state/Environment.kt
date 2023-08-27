/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.internal.postman.state

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true) internal data class Environment(val values: List<EnvValue>)

@JsonClass(generateAdapter = true)
internal data class EnvValue(val key: String, val value: String?, val enabled: Boolean)
