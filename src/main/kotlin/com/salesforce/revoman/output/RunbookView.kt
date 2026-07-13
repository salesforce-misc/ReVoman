/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

import com.salesforce.revoman.input.config.RunbookStep

private fun outcomeOf(rundown: Rundown): String =
  if (rundown.firstUnIgnoredUnsuccessfulStepReport == null) "OK" else "FAIL"

private fun producedText(step: RunbookStep): String =
  if (step.produces.isEmpty()) "—"
  else step.produces.entries.joinToString(", ") { (k, v) -> if (v == null) k else "$k=$v" }

private fun consumedText(step: RunbookStep): String =
  if (step.consumes.isEmpty()) "—" else step.consumes.joinToString(", ")

/** A markdown table runbook: one row per step. Pure function of the declared+executed runbook. */
internal fun renderRunbookMarkdown(rr: RunbookRundown): String {
  val title = rr.name?.let { "### Runbook: $it\n\n" } ?: ""
  val header = "| Phase | Step | Consumes | Produces | Outcome |\n|---|---|---|---|---|\n"
  val rows =
    rr.stepRundowns.joinToString("\n") { (step, rundown) ->
      "| ${step.phase} | ${step.intent} | ${consumedText(step)} | ${producedText(step)} | ${outcomeOf(rundown)} |"
    }
  return title + header + rows + "\n"
}

/**
 * A mermaid sequence diagram: a `Runbook` participant issuing one message per step, annotated with
 * consumed and produced keys, and under-test marker. Theme is applied by whoever writes it into
 * docs.
 */
internal fun renderRunbookMermaid(rr: RunbookRundown): String {
  val lines =
    rr.stepRundowns.flatMap { (step, _) ->
      val marker = if (step.underTest) "◆ " else ""
      val consumes = "⟵ ${consumedText(step)}"
      val produces = "⟶ ${producedText(step)}"
      listOf(
        "    Runbook->>${step.phase}: ${step.intent}",
        "    Note right of ${step.phase}: $marker$consumes  $produces",
      )
    }
  return (listOf("sequenceDiagram", "    participant Runbook") + lines).joinToString("\n") + "\n"
}
