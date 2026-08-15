/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.testing.http.internal

import com.salesforce.revoman.testing.http.RecordedHttpRequest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A handler failure associated with the capture order assigned to its request. */
internal data class HandlerFailure(val ordinal: Long, val failure: Exception)

/**
 * Synchronizes complete request publication, capture ordering, and immutable observer snapshots.
 *
 * A request receives an ordinal only after it has been fully materialized into a
 * [RecordedHttpRequest], so readers can never observe a partial capture.
 */
internal class RequestLedger {
  private val lock = ReentrantLock()
  private var nextOrdinal = 0L
  private val records = mutableListOf<Pair<Long, RecordedHttpRequest>>()
  private val failures = mutableListOf<HandlerFailure>()

  /** Publishes a complete captured request and returns its monotonic capture ordinal. */
  fun publish(request: RecordedHttpRequest): Long = lock.withLock {
    val ordinal = nextOrdinal++
    records += ordinal to request
    ordinal
  }

  /**
   * Records a handler failure for deterministic close-time reporting in a later lifecycle phase.
   */
  fun recordHandlerFailure(ordinal: Long, failure: Exception): Unit = lock.withLock {
    failures += HandlerFailure(ordinal, failure)
  }

  /** Returns an unmodifiable point-in-time snapshot in capture order. */
  fun requests(): List<RecordedHttpRequest> = lock.withLock {
    java.util.List.copyOf(records.map { it.second })
  }

  /** Returns handler failures sorted by their request capture order. */
  fun handlerFailures(): List<HandlerFailure> = lock.withLock {
    java.util.List.copyOf(failures.sortedBy(HandlerFailure::ordinal))
  }

  /** Aggregates retained handler and shutdown failures in deterministic close-time order. */
  fun aggregateCloseFailure(shutdownFailures: List<Throwable>): IllegalStateException? {
    val handlerFailures = handlerFailures().map(HandlerFailure::failure)
    val orderedFailures = handlerFailures + shutdownFailures
    if (orderedFailures.isEmpty()) return null
    val message =
      if (handlerFailures.isEmpty()) {
        "Mock HTTP server shutdown failed"
      } else {
        "${handlerFailures.size} mock HTTP handler failures"
      }
    return IllegalStateException(message, orderedFailures.first()).apply {
      orderedFailures.drop(1).forEach(::addSuppressed)
    }
  }
}
