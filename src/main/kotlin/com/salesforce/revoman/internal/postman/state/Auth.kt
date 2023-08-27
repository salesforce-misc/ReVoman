/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.internal.postman.state

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
internal data class Auth(val bearer: List<Bearer>, val type: String)

@JsonClass(generateAdapter = true)
internal data class Bearer(val key: String, val type: String, val value: String)
