/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.log

/**
 * The single source of truth for rendering a run's [RunInteraction] list to a Mermaid
 * `sequenceDiagram`. Pure and stateless (a function of its argument only), mirroring
 * [RunLogRenderer]: the diagram grammar lives ONCE here so no consumer can drift it. Chosen over
 * PlantUML because Mermaid renders natively in GitHub/IDEs and needs no external tool.
 *
 * The diagram carries four things beyond "who called whom":
 * - one `participant` per distinct host (first-seen order, stable id `h<n>`),
 * - a phase note whenever the enclosing runbook phase changes,
 * - a data-flow note where a step consumes an env key an earlier step produced (when several hosts
 *   produced the same key, the most recent producer is linked),
 * - and a trailing inefficiency summary flagging duplicate `(method, host, path)` calls.
 */
object DiagramRenderer {

  /** Render [interactions] to a Mermaid `sequenceDiagram` block (newline-terminated). */
  @JvmStatic
  fun render(interactions: List<RunInteraction>): String {
    val header = "sequenceDiagram\n    actor User\n"
    if (interactions.isEmpty()) return header
    val hostIds: Map<String, String> = assignHostIds(interactions)
    val participants =
      hostIds.entries.joinToString("") { (host, id) -> "    participant $id as $host\n" }
    val body = renderBody(interactions, hostIds)
    val inefficiency = renderInefficiency(interactions)
    return header + participants + body + inefficiency
  }

  /** Distinct hosts mapped to stable ids `h0, h1, …` in first-seen order. */
  private fun assignHostIds(interactions: List<RunInteraction>): Map<String, String> =
    interactions.map { it.to }.distinct().withIndex().associate { (i, host) -> host to "h$i" }

  private fun renderBody(
    interactions: List<RunInteraction>,
    hostIds: Map<String, String>,
  ): String {
    // Which host most recently produced each key (toMap keeps the last write for a duplicate key),
    // so a later consumer's data-flow note points back to that producer's host id.
    val producedBy: Map<String, String> =
      interactions.flatMap { i -> i.produced.map { key -> key to hostIds.getValue(i.to) } }.toMap()
    val sb = StringBuilder()
    var currentPhase: String? = null
    for (i in interactions) {
      if (i.phase != null && i.phase != currentPhase) {
        sb.append("    Note over User: ━━ ${i.phase}\n")
        currentPhase = i.phase
      }
      val id = hostIds.getValue(i.to)
      sb.append("    User->>$id: ${i.method} ${i.requestPath}\n")
      val statusText = i.status?.toString() ?: "ERR"
      val outcomeMark = if (i.outcome == Outcome.SUCCESS) "" else " ✘"
      sb.append("    $id-->>User: $statusText (${i.tookMs}ms)$outcomeMark\n")
      i.consumed
        .sorted()
        .mapNotNull { key -> producedBy[key]?.let { producerId -> key to producerId } }
        .filter { (_, producerId) -> producerId != id }
        .forEach { (key, producerId) ->
          sb.append("    Note right of $id: ⟵ $key from $producerId\n")
        }
    }
    return sb.toString()
  }

  /** Trailing `⚠ n× METHOD host/path` note per duplicated call; empty when nothing repeats. */
  private fun renderInefficiency(interactions: List<RunInteraction>): String =
    interactions
      .groupingBy { "${it.method} ${it.to}${it.requestPath}" }
      .eachCount()
      .filter { it.value > 1 }
      .entries
      .joinToString("") { (call, count) -> "    Note over User: ⚠ ${count}× $call\n" }
}
