/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

internal interface InternalCloseable {
  @JvmSynthetic fun close()
}

@JvmSynthetic
internal inline fun <T : InternalCloseable, R> T.useInternal(block: (T) -> R): R {
  var primary: Throwable? = null
  try {
    return block(this)
  } catch (failure: Throwable) {
    primary = failure
    throw failure
  } finally {
    when {
      this is ResourceScope -> {
        val finalFailure = closeAfter(primary)
        if (primary == null && finalFailure != null) throw finalFailure
      }
      primary == null -> close()
      else ->
        try {
          close()
        } catch (closeFailure: Throwable) {
          if (primary !== closeFailure) primary.addSuppressed(closeFailure)
        }
    }
  }
}
