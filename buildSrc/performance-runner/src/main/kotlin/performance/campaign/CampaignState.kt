package performance.campaign

import performance.capture.CaptureOutcome
import performance.model.CaptureIdentity

data class SessionIdentity(val campaignId: String, val performanceSessionId: String)

enum class CampaignStatus { CALIBRATING, QUALIFIED, INVALID, CALIBRATION_EXHAUSTED }

data class CampaignCapture(
  val role: CaptureRole,
  val forks: Int,
  val identity: CaptureIdentity,
  val outcome: CaptureOutcome,
  val selected: Boolean,
)

data class CampaignProvisionalOutcome(
  val status: CampaignStatus,
  val captures: List<CampaignCapture>,
  val selectedA1: CampaignCapture? = null,
  val selectedA2: CampaignCapture? = null,
  val candidate: CampaignCapture? = null,
  val reason: String? = null,
) {
  val claimEligible: Boolean get() = status == CampaignStatus.QUALIFIED
}
