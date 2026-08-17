package performance.campaign

enum class CaptureRole(val candidate: Boolean) {
  BASELINE_A1(false),
  BASELINE_A2(false),
  CANDIDATE_B(true),
}

object SessionOrder {
  val selected: List<CaptureRole> = listOf(
    CaptureRole.BASELINE_A1,
    CaptureRole.BASELINE_A2,
    CaptureRole.CANDIDATE_B,
  )
}
