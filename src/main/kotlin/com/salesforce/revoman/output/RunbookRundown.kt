/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import com.salesforce.revoman.input.config.RunbookStep

/**
 * The executed runbook: each [RunbookStep] paired with its [Rundown], in order. Implements
 * `List<Rundown>` so it drops in wherever today's `revUp(List<Kick>)` return is consumed, while
 * adding step pairing and (Task 8) `toMermaid()`/`toMarkdown()` views.
 */
class RunbookRundown(
  val name: String?,
  val stepRundowns: List<Pair<RunbookStep, Rundown>>,
) : List<Rundown> {

  val rundowns: List<Rundown> = stepRundowns.map { it.second }

  override val size: Int
    get() = rundowns.size

  override fun contains(element: Rundown): Boolean = rundowns.contains(element)

  override fun containsAll(elements: Collection<Rundown>): Boolean = rundowns.containsAll(elements)

  override fun get(index: Int): Rundown = rundowns[index]

  override fun indexOf(element: Rundown): Int = rundowns.indexOf(element)

  override fun isEmpty(): Boolean = rundowns.isEmpty()

  override fun iterator(): Iterator<Rundown> = rundowns.iterator()

  override fun lastIndexOf(element: Rundown): Int = rundowns.lastIndexOf(element)

  override fun listIterator(): ListIterator<Rundown> = rundowns.listIterator()

  override fun listIterator(index: Int): ListIterator<Rundown> = rundowns.listIterator(index)

  override fun subList(fromIndex: Int, toIndex: Int): List<Rundown> =
    rundowns.subList(fromIndex, toIndex)

  fun stepFor(intent: String): Pair<RunbookStep, Rundown>? = stepRundowns.firstOrNull {
    it.first.intent == intent
  }
}
