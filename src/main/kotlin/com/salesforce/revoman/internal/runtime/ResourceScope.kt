/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

internal interface ResourceScope : InternalCloseable {
  @JvmSynthetic fun <T : InternalCloseable> own(resource: T): T

  @JvmSynthetic fun closeAfter(primary: Throwable?): Throwable?

  @JvmSynthetic override fun close()
}

@JvmSynthetic
internal fun resourceScope(): ResourceScope {
  return object : ResourceScope {
    private val resources = ArrayDeque<InternalCloseable>()
    private var closed = false

    override fun <T : InternalCloseable> own(resource: T): T {
      if (closed) {
        val failure = IllegalStateException("ResourceScope is already closed")
        try {
          resource.close()
        } catch (closeFailure: Throwable) {
          failure.addSuppressed(closeFailure)
        }
        throw failure
      }
      resources.addLast(resource)
      return resource
    }

    override fun closeAfter(primary: Throwable?): Throwable? {
      if (closed) return primary
      closed = true
      var failure = primary
      while (resources.isNotEmpty()) {
        try {
          resources.removeLast().close()
        } catch (closeFailure: Throwable) {
          when {
            failure == null -> failure = closeFailure
            failure !== closeFailure -> failure.addSuppressed(closeFailure)
          }
        }
      }
      return failure
    }

    override fun close() {
      closeAfter(null)?.let { throw it }
    }
  }
}
