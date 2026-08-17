package performance.campaign

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly

class SessionOrderTest : FunSpec({
  test("the selected candidate order is A1, A2, B") {
    SessionOrder.selected shouldContainExactly listOf(CaptureRole.BASELINE_A1, CaptureRole.BASELINE_A2, CaptureRole.CANDIDATE_B)
  }
})
