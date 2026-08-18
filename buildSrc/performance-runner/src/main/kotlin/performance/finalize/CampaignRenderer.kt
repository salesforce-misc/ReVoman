/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.finalize

import performance.json.CanonicalJson
import performance.model.CampaignAttemptRecord
import performance.model.CampaignCandidateRecord
import performance.model.CampaignCaptureRecord
import performance.model.CampaignDocument
import performance.model.CampaignFileFact
import performance.model.CampaignImplementationRecord
import performance.model.CampaignQualificationRecord
import performance.model.CampaignReceiptRecord
import performance.model.CampaignStatus
import performance.model.HostDocumentRef
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

internal object CampaignRenderer {
  @JvmSynthetic
  fun render(
    @Suppress("UNUSED_PARAMETER") permit: CampaignMaterializationPermit,
    document: CampaignDocument,
  ): ByteArray = CanonicalJson.encode(document.toJson())

  private fun CampaignDocument.toJson(): ObjectNode = objectNode {
    set("attempts", arrayNode(attempts.map { it.toJson() }))
    put("campaignId", campaignId)
    candidate?.let { set("candidate", it.toJson()) }
    put("claimEligible", status == CampaignStatus.QUALIFIED)
    set("implementation", implementation.toJson())
    put("performanceSessionId", performanceSessionId)
    set("qualification", qualification.toJson())
    put("reason", if (status == CampaignStatus.QUALIFIED) "candidateMeasured" else "calibrationExhausted")
    put("schemaVersion", "campaign-v1")
    selectedAttemptId?.let { put("selectedAttemptId", it) }
    put("status", status.name.lowercase())
    put("strength", if (status == CampaignStatus.QUALIFIED) "canonical" else "diagnostic")
  }

  private fun CampaignAttemptRecord.toJson(): ObjectNode = objectNode {
    set("a1", a1.toJson())
    set("a2", a2.toJson())
    put("attemptId", attemptId)
    b?.let { set("b", it.toJson()) }
    set("calibration", objectNode {
      put("bundleSha256", calibrationBundleSha256.hex)
      put("passed", calibrationPassed)
      put("path", calibrationPath)
    })
    put("forks", forks)
    set("receipts", arrayNode(receipts.map { it.toJson() }))
  }

  private fun CampaignCaptureRecord.toJson(): ObjectNode = objectNode {
    put("bundleSha256", bundleSha256.hex)
    put("captureId", captureId)
    put("captureSha256", captureSha256.hex)
    put("path", path)
    put("processRunId", processRunId)
    put("role", role)
    put("sequence", sequence)
  }

  private fun CampaignReceiptRecord.toJson(): ObjectNode = objectNode {
    put("distribution", distribution)
    set("files", arrayNode(files.map { it.toJson() }))
    put("manifestSha256", manifestSha256.hex)
    put("role", role)
    put("sequence", sequence)
    put("settleMillis", settleMillis)
  }

  private fun CampaignFileFact.toJson(): ObjectNode = objectNode {
    put("byteLength", byteLength)
    put("path", path)
    put("sha256", sha256.hex)
  }

  private fun CampaignCandidateRecord.toJson(): ObjectNode = objectNode {
    put("bundleSha256", bundleSha256.hex)
    put("exit", exit.code)
    put("path", path)
    put("policyOutcome", policyOutcome)
    policySha256?.let { put("policySha256", it.hex) }
  }

  private fun CampaignQualificationRecord.toJson(): ObjectNode = objectNode {
    put("cleanupPassed", true)
    put("policySha256", policySha256.hex)
    set("postflight", postflight.toJson())
    set("preflight", preflight.toJson())
    set("restoration", restoration.toJson())
    set("watcher", watcher.toJson())
  }

  private fun CampaignImplementationRecord.toJson(): ObjectNode = objectNode {
    put("adapterSha256", adapterSha256.hex)
    put("comparatorSha256", comparatorSha256.hex)
    put("protocolSha256", protocolSha256.hex)
    put("rendererSha256", rendererSha256.hex)
    put("runnerSha256", runnerSha256.hex)
  }

  private fun HostDocumentRef.toJson(): ObjectNode = objectNode {
    put("path", path)
    put("sha256", sha256.hex)
  }

  private fun objectNode(block: ObjectNode.() -> Unit): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply(block)

  private fun arrayNode(values: List<ObjectNode>) =
    JsonNodeFactory.instance.arrayNode().also { array -> values.forEach(array::add) }
}
