package com.salesforce.revoman.input.config

import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.Rundown.StepReport
import com.salesforce.revoman.output.Rundown.StepReport.TxInfo
import org.http4k.core.Request

sealed interface StepPick {
  fun interface PreTxnStepPick : StepPick {
    @Throws(Throwable::class)
    fun pick(
      currentStepName: String,
      currentRequestInfo: TxInfo<Request>,
      rundown: Rundown
    ): Boolean
  }

  fun interface PostTxnStepPick : StepPick {
    @Throws(Throwable::class)
    fun pick(currentStepName: String, currentStepReport: StepReport, rundown: Rundown): Boolean
  }
}
