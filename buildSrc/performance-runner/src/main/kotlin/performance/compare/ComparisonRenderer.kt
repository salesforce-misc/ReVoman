/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.compare

import performance.json.CanonicalJson
import performance.model.ComparisonCalibrationRef
import performance.model.ComparisonCaptureRef
import performance.model.ComparisonCellResult
import performance.model.ComparisonDocument
import performance.model.ComparisonPolicyResult
import performance.model.ComparisonReportDocument
import performance.model.IncompatibleComparisonDocument
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

class RenderedComparison(
  val json: ByteArray,
  val markdown: ByteArray,
)

/** Locale/timezone-independent strict JSON and concise Markdown rendering. */
class ComparisonRenderer {
  fun render(document: ComparisonReportDocument): RenderedComparison =
    RenderedComparison(
      json = CanonicalJson.encode(document.toJson()),
      markdown = document.markdown().encodeToByteArray(),
    )

  private fun ComparisonReportDocument.toJson(): ObjectNode =
    when (this) {
      is ComparisonDocument -> toCompatibleJson()
      is IncompatibleComparisonDocument -> toIncompatibleJson()
    }

  private fun ComparisonDocument.toCompatibleJson(): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("schemaVersion", schemaVersion)
      put("kind", kind.wire())
      put("strength", strength.wire())
      put("compatibility", compatibility.wire())
      set(
        "compatibilityReasons",
        JsonNodeFactory.instance.arrayNode().also { array ->
          compatibilityReasons.forEach { reason -> array.add(reason.wire()) }
        },
      )
      set("baseline", baseline.toJson())
      set("candidate", candidate.toJson())
      set("implementation", implementation.toJson())
      set(
        "cells",
        JsonNodeFactory.instance.arrayNode().also { array ->
          cells.forEach { cell -> array.add(cell.toJson()) }
        },
      )
      calibration?.let { set("calibration", it.toJson()) }
      set("policy", policy.toJson())
    }

  private fun IncompatibleComparisonDocument.toIncompatibleJson(): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("schemaVersion", schemaVersion)
      put("kind", kind.wire())
      put("strength", strength.wire())
      put("compatibility", compatibility.wire())
      set(
        "compatibilityReasons",
        JsonNodeFactory.instance.arrayNode().also { array ->
          compatibilityReasons.forEach { reason -> array.add(reason.wire()) }
        },
      )
      set("implementation", implementation.toJson())
    }

  private fun ComparisonExecutionIdentity.toJson(): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("adapterSha256", adapterSha256.hex)
      put("bootstrapVectorSha256", bootstrapVectorSha256.hex)
      put("captureSchemaSha256", captureSchemaSha256.hex)
      put("comparatorSha256", comparatorSha256.hex)
      put("comparisonSchemaSha256", comparisonSchemaSha256.hex)
      put("expectedCellsSha256", expectedCellsSha256.hex)
      put("protocolSha256", protocolSha256.hex)
      put("qualificationPolicySha256", qualificationPolicySha256.hex)
      put("rendererSha256", rendererSha256.hex)
      put("runnerSha256", runnerSha256.hex)
    }

  private fun ComparisonCaptureRef.toJson(): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("bundleSha256", bundleSha256.hex)
      put("captureId", captureId)
      put("captureSha256", captureSha256.hex)
      put("productionSha256", productionSha256.hex)
      put("treatmentGitSha", treatmentGitSha)
    }

  private fun ComparisonCellResult.toJson(): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      set(
        "identity",
        JsonNodeFactory.instance.objectNode().apply {
          put("batchSize", identity.batchSize)
          put("benchmark", identity.benchmark)
          put("direction", identity.direction)
          put("mode", identity.mode)
          set(
            "parameters",
            JsonNodeFactory.instance.objectNode().apply {
              identity.parameters.forEach { (name, value) -> put(name, value) }
            },
          )
          put("primaryMetric", identity.primaryMetric)
          put("profile", identity.profile)
          put("threads", identity.threads)
          put("unit", identity.unit)
        },
      )
      set(
        "estimate",
        JsonNodeFactory.instance.objectNode().apply {
          put("gainPercent", estimate.gainPercent)
          put("lower95Ratio", estimate.lower95Ratio)
          put("pointRatio", estimate.pointRatio)
          put("upper95Ratio", estimate.upper95Ratio)
        },
      )
      put("directionOutcome", direction.wire())
      put("policyOutcome", policy.wire())
    }

  private fun ComparisonCalibrationRef.toJson(): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("a1CaptureId", a1CaptureId)
      put("a2CaptureId", a2CaptureId)
      bCaptureId?.let { put("bCaptureId", it) }
      put("passed", passed)
      evidenceSha256?.let { put("evidenceSha256", it.hex) }
    }

  private fun ComparisonPolicyResult.toJson(): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply {
      maximumRegressionBudget?.let { put("maximumRegressionBudget", it) }
      maximumCandidateBaselineRatio?.let { put("maximumCandidateBaselineRatio", it) }
      put("outcome", outcome.wire())
      sha256?.let { put("sha256", it.hex) }
    }

  private fun ComparisonReportDocument.markdown(): String =
    when (this) {
      is ComparisonDocument -> compatibleMarkdown()
      is IncompatibleComparisonDocument -> incompatibleMarkdown()
    }

  private fun ComparisonDocument.compatibleMarkdown(): String =
    buildString {
      appendLine("# ReVoman performance comparison")
      appendLine()
      appendLine("- Kind: `${kind.wire()}`")
      appendLine("- Strength: `${strength.wire()}`")
      appendLine("- Compatibility: `${compatibility.wire()}`")
      appendLine("- Baseline capture: `${baseline.captureId}`")
      appendLine("- Baseline capture SHA-256: `${baseline.captureSha256.hex}`")
      appendLine("- Baseline bundle SHA-256: `${baseline.bundleSha256.hex}`")
      appendLine("- Baseline treatment Git SHA: `${baseline.treatmentGitSha}`")
      appendLine("- Baseline production SHA-256: `${baseline.productionSha256.hex}`")
      appendLine("- Candidate capture: `${candidate.captureId}`")
      appendLine("- Candidate capture SHA-256: `${candidate.captureSha256.hex}`")
      appendLine("- Candidate bundle SHA-256: `${candidate.bundleSha256.hex}`")
      appendLine("- Candidate treatment Git SHA: `${candidate.treatmentGitSha}`")
      appendLine("- Candidate production SHA-256: `${candidate.productionSha256.hex}`")
      appendLine("- Comparator SHA-256: `${implementation.comparatorSha256.hex}`")
      appendLine("- Renderer SHA-256: `${implementation.rendererSha256.hex}`")
      appendLine("- Runner SHA-256: `${implementation.runnerSha256.hex}`")
      appendLine("- Protocol SHA-256: `${implementation.protocolSha256.hex}`")
      appendLine("- Adapter SHA-256: `${implementation.adapterSha256.hex}`")
      appendLine("- Expected cells SHA-256: `${implementation.expectedCellsSha256.hex}`")
      appendLine("- Capture schema SHA-256: `${implementation.captureSchemaSha256.hex}`")
      appendLine("- Comparison schema SHA-256: `${implementation.comparisonSchemaSha256.hex}`")
      appendLine("- Bootstrap vector SHA-256: `${implementation.bootstrapVectorSha256.hex}`")
      appendLine(
        "- Qualification policy SHA-256: `${implementation.qualificationPolicySha256.hex}`"
      )
      calibration?.let {
        appendLine("- Calibration A1 capture: `${it.a1CaptureId}`")
        appendLine("- Calibration A2 capture: `${it.a2CaptureId}`")
        it.bCaptureId?.let { bCaptureId ->
          appendLine("- Calibration B capture: `$bCaptureId`")
        }
        appendLine("- Calibration passed: `${it.passed}`")
      }
      calibration?.evidenceSha256?.let {
        appendLine("- Calibration evidence SHA-256: `${it.hex}`")
      }
      policy.sha256?.let { appendLine("- Regression policy SHA-256: `${it.hex}`") }
      policy.maximumRegressionBudget?.let {
        appendLine("- Maximum regression budget: `$it`")
      }
      policy.maximumCandidateBaselineRatio?.let {
        appendLine("- Maximum candidate/baseline ratio: `$it`")
      }
      appendLine("- Policy outcome: `${policy.outcome.wire()}`")
      appendLine()
      appendLine("## Cells")
      cells.forEach { cell ->
        appendLine()
        appendLine("### `${cell.identity.benchmark}`")
        appendLine("- Profile: `${cell.identity.profile}`")
        appendLine(
          "- Parameters: `${cell.identity.parameters.toSortedMap().entries.joinToString(",", "{", "}") { (name, value) -> "$name=$value" }}`"
        )
        appendLine("- Candidate/baseline point ratio: `${cell.estimate.pointRatio}`")
        appendLine(
          "- Candidate/baseline ratio interval (95% conditional fork resampling): " +
            "`[${cell.estimate.lower95Ratio}, ${cell.estimate.upper95Ratio}]`"
        )
        appendLine("- Point gain: `${cell.estimate.gainPercent}%`")
        appendLine("- Direction: `${cell.direction.wire()}`")
        appendLine("- Policy: `${cell.policy.wire()}`")
      }
      appendLine()
      appendLine(
        "The ratio interval describes conditional fork-resampling uncertainty within this " +
          "captured session; it does not estimate between-day or between-session host drift."
      )
    }

  private fun IncompatibleComparisonDocument.incompatibleMarkdown(): String =
    buildString {
      appendLine("# ReVoman performance comparison")
      appendLine()
      appendLine("- Kind: `${kind.wire()}`")
      appendLine("- Strength: `${strength.wire()}`")
      appendLine("- Compatibility: `${compatibility.wire()}`")
      appendLine("- Comparator SHA-256: `${implementation.comparatorSha256.hex}`")
      appendLine("- Renderer SHA-256: `${implementation.rendererSha256.hex}`")
      appendLine("- Runner SHA-256: `${implementation.runnerSha256.hex}`")
      appendLine("- Protocol SHA-256: `${implementation.protocolSha256.hex}`")
      appendLine("- Adapter SHA-256: `${implementation.adapterSha256.hex}`")
      appendLine("- Expected cells SHA-256: `${implementation.expectedCellsSha256.hex}`")
      appendLine("- Capture schema SHA-256: `${implementation.captureSchemaSha256.hex}`")
      appendLine("- Comparison schema SHA-256: `${implementation.comparisonSchemaSha256.hex}`")
      appendLine("- Bootstrap vector SHA-256: `${implementation.bootstrapVectorSha256.hex}`")
      appendLine(
        "- Qualification policy SHA-256: `${implementation.qualificationPolicySha256.hex}`"
      )
      appendLine()
      appendLine("## Compatibility reasons")
      compatibilityReasons.forEach { reason -> appendLine("- `${reason.wire()}`") }
      appendLine()
      appendLine("No estimates were computed because compatibility validation failed.")
    }
}

private fun ComparisonKind.wire(): String = name.lowercase()

private fun ComparisonStrength.wire(): String = name.lowercase()

private fun ComparisonCompatibility.wire(): String = name.lowercase()

private fun DirectionOutcome.wire(): String = name.lowercase()

private fun PolicyOutcome.wire(): String = name.lowercase().replace('_', '-')

private fun CompatibilityFailure.wire(): String = name.lowercase().replace('_', '-')
