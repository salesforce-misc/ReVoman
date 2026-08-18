/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.finalize

import java.nio.file.Path
import java.time.Duration
import performance.campaign.CaptureRole
import performance.campaign.ReceiptFileFact
import performance.compare.CaptureComparator
import performance.compare.RegressionPolicy
import performance.hash.Sha256
import performance.model.QualificationEvidence
import performance.runner.RunnerExit

internal data class CampaignCaptureInput(
  val role: CaptureRole,
  val sealedRoot: Path,
  val receipt: CampaignReceiptInput,
)

internal data class CampaignReceiptInput(
  val role: CaptureRole,
  val distributionRoot: Path,
  val manifestSha256: Sha256,
  val files: List<ReceiptFileFact>,
  val settleDuration: Duration,
  val sequence: Long,
)

internal data class CampaignAttemptInput(
  val attemptId: String,
  val forks: Int,
  val a1: CampaignCaptureInput,
  val a2: CampaignCaptureInput,
  val b: CampaignCaptureInput?,
  val calibrationPassed: Boolean = b != null,
)

internal data class CampaignComputationRequest(
  val campaignId: String,
  val performanceSessionId: String,
  val attempts: List<CampaignAttemptInput>,
  val baselineDistribution: Path,
  val candidateDistribution: Path,
  val qualificationRoot: Path,
  val qualification: QualificationEvidence.ControlledMacCampaign,
  val regressionPolicy: RegressionPolicy?,
  val outputRoot: Path,
  val selectedAttemptId: String? = attempts.lastOrNull { it.b != null }?.attemptId,
)

internal enum class CampaignRejection {
  INVALID_INPUT,
  INTERNAL_FAILURE,
}

internal sealed interface CampaignComputationOutcome {
  class Computed internal constructor(
    mint: Any,
    val root: Path,
    val campaignSha256: Sha256,
    val manifestSha256: Sha256,
    val exit: RunnerExit,
  ) : CampaignComputationOutcome {
    init {
      require(mint === COMPUTED_MINT) { "campaign result must be finalizer-minted" }
    }
  }

  data class Rejected(
    val reason: CampaignRejection,
    val exit: RunnerExit,
  ) : CampaignComputationOutcome
}

private val COMPUTED_MINT = Any()
private val MATERIALIZATION_MINT = Any()

internal class CampaignMaterializationPermit internal constructor(mint: Any) {
  init {
    require(mint === MATERIALIZATION_MINT) { "campaign materialization must be finalizer-authorized" }
  }
}

/** Sole campaign-computation entry point; callers provide sealed paths and declared graph only. */
internal class CampaignFinalizer(
  private val comparator: CaptureComparator = CaptureComparator(),
) {
  fun compute(request: CampaignComputationRequest): CampaignComputationOutcome {
    // Reverification is deliberately the first evidence action. No caller-provided proof survives it.
    val verifications = CampaignValidator.verifyCapturePaths(request)
    val verified = CampaignValidator.validate(request, verifications, comparator)
      ?: return CampaignComputationOutcome.Rejected(
        CampaignRejection.INVALID_INPUT,
        RunnerExit.INPUT_OR_PREFLIGHT_INVALID,
      )
    return runCatching {
        val materialized =
          CampaignMaterializer.materialize(
            CampaignMaterializationPermit(MATERIALIZATION_MINT),
            verified,
            comparator,
          )
        CampaignComputationOutcome.Computed(
          COMPUTED_MINT,
          materialized.root,
          materialized.campaignSha256,
          materialized.manifestSha256,
          materialized.exit,
        )
      }
      .getOrElse {
        CampaignComputationOutcome.Rejected(
          CampaignRejection.INTERNAL_FAILURE,
          RunnerExit.INTERNAL_OR_PUBLICATION_FAILED,
        )
      }
  }
}
