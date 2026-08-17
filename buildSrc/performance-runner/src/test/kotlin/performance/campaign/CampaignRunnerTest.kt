package performance.campaign

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CampaignRunnerTest : FunSpec({
  test("the frozen ladder escalates 10 to 20 to 40 and never launches B after the final miss") {
    ProfileFamily.WARM.forkLadder shouldBe listOf(10, 20, 40)
  }
})
