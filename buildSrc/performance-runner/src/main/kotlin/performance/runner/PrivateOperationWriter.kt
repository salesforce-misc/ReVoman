/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.runner

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import performance.campaign.CampaignCapture
import performance.campaign.CampaignProvisionalOutcome
import performance.campaign.CampaignRequest
import performance.campaign.PreconditioningReceipt
import performance.capture.CaptureOutcome
import performance.capture.CaptureRequest
import performance.finalize.CaptureDocumentCodec
import performance.json.CanonicalJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** Writes only private, canonical operation state; it has no public artifact capability. */
internal object PrivateOperationWriter {
  fun capture(
    request: CaptureRequest,
    outcome: CaptureOutcome.Provisional,
  ) {
    val state = stateRoot(request.provisionalRoot)
    val bytes = CaptureDocumentCodec.encode(outcome.document)
    writeFsynced(state.resolve(CAPTURE_STATE), bytes)
    writeProfilerState(state, request, bytes, outcome)
    fsync(state)
  }

  fun campaign(
    request: CampaignRequest,
    outcome: CampaignProvisionalOutcome,
    flags: Map<String, String>,
  ) {
    val state = stateRoot(request.provisionalRoot)
    val captures =
      outcome.captures.mapIndexed { index, capture ->
        val provisional = capture.outcome as? CaptureOutcome.Provisional
          ?: error("campaign contains an invalid capture")
        val name = "campaign-capture-${index + 1}.json"
        writeFsynced(state.resolve(name), CaptureDocumentCodec.encode(provisional.document))
        render(capture, name)
      }
    val document =
      objectNode {
        put("baselineDistribution", request.baseline.root.toString())
        put("campaignId", request.session.campaignId)
        put("candidateDistribution", request.candidate.root.toString())
        set("captures", arrayNode(captures))
        put("performanceSessionId", request.session.performanceSessionId)
        flags["--regression-policy"]?.let { path ->
          set("regressionPolicy", CanonicalJson.parseStrict(Files.readAllBytes(Path.of(path))))
        }
        put("schemaVersion", "private-campaign-operation-v1")
        put("status", outcome.status.name)
        outcome.reason?.let { put("reason", it.name) }
      }
    writeFsynced(state.resolve(CAMPAIGN_STATE), CanonicalJson.encode(document))
    fsync(state)
  }

  private fun render(capture: CampaignCapture, documentFile: String): ObjectNode =
    objectNode {
      put("attemptId", capture.attemptId)
      put("documentFile", documentFile)
      put("forks", capture.forks)
      put("operationRoot", capture.operationRoot.toString())
      set("receipt", render(capture.preconditioningReceipt))
      put("role", capture.role.name)
      put("selected", capture.selected)
    }

  private fun render(receipt: PreconditioningReceipt): ObjectNode =
    objectNode {
      put("distributionRoot", receipt.distributionRoot.toString())
      set(
        "files",
        arrayNode(
          receipt.files.map { fact ->
            objectNode {
              put("byteLength", fact.byteLength)
              put("relativePath", fact.relativePath)
              put("sha256", fact.sha256.hex)
            }
          },
        ),
      )
      put("manifestSha256", receipt.manifestSha256.hex)
      put("role", receipt.role.name)
      put("sequence", receipt.sequence)
      put("settleDurationMillis", receipt.settleDuration.toMillis())
    }

  private fun stateRoot(operationRoot: Path): Path {
    val state = operationRoot.resolveSibling(STATE_DIRECTORY).toAbsolutePath().normalize()
    require(state.parent == operationRoot.parent)
    if (!Files.exists(state, NOFOLLOW_LINKS)) Files.createDirectory(state)
    require(Files.isDirectory(state, NOFOLLOW_LINKS) && !Files.isSymbolicLink(state))
    return state
  }

  private fun writeProfilerState(
    state: Path,
    request: CaptureRequest,
    bytes: ByteArray,
    outcome: CaptureOutcome.Provisional,
  ) {
    if (request.profile.profiler.id == "none") return
    val document = outcome.document
    writeFsynced(state.resolve("profiler-capture-id"), "${document.identity.captureId}\n".encodeToByteArray())
    writeFsynced(state.resolve("profiler-provisional-capture.sha256"), "${performance.hash.Sha256.digest(bytes).hex}\n".encodeToByteArray())
    writeFsynced(state.resolve("profiler-raw-input.sha256"), "${document.artifacts.rawJmhInputSha256.hex}\n".encodeToByteArray())
    writeFsynced(state.resolve("profiler-variant.sha256"), "${document.profile.variantSha256.hex}\n".encodeToByteArray())
    request.profile.profilerSettingsSha256?.let { hash ->
      writeFsynced(state.resolve("profiler-settings.sha256"), "${hash.hex}\n".encodeToByteArray())
    }
  }

  private fun writeFsynced(path: Path, bytes: ByteArray) {
    FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
      val buffer = ByteBuffer.wrap(bytes)
      while (buffer.hasRemaining()) channel.write(buffer)
      channel.force(true)
    }
  }

  private fun fsync(path: Path) {
    FileChannel.open(path, READ).use { it.force(true) }
  }

  private fun objectNode(block: ObjectNode.() -> Unit): ObjectNode =
    JsonNodeFactory.instance.objectNode().apply(block)

  private fun arrayNode(values: List<JsonNode>): ArrayNode =
    JsonNodeFactory.instance.arrayNode().apply { values.forEach(::add) }

  const val CAPTURE_STATE = "capture-provisional.json"
  const val CAMPAIGN_STATE = "campaign-provisional.json"
  const val QUALIFICATION_STATE = "qualification.json"
  private const val STATE_DIRECTORY = "state"
}
