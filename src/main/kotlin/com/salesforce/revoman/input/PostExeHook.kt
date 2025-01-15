/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input

import com.salesforce.revoman.output.Rundown

fun interface PostExeHook {
  @Throws(Throwable::class) fun accept(currentRundown: Rundown, rundowns: List<Rundown>)
}
