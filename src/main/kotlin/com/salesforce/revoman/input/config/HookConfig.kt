package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.HookConfig.Hook.PostHook
import com.salesforce.revoman.input.config.HookConfig.Hook.PreHook
import com.salesforce.revoman.input.config.HookConfig.HookType.POST
import com.salesforce.revoman.input.config.HookConfig.HookType.PRE
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.salesforce.revoman.output.Rundown
import io.vavr.control.Either
import io.vavr.kotlin.left
import io.vavr.kotlin.right
import org.http4k.core.Request

data class HookConfig
private constructor(val pick: Either<Pair<HookType, String>, StepPick>, val hook: Hook) {
  enum class HookType {
    PRE,
    POST,
  }

  sealed interface Hook {
    fun interface PreHook : Hook {
      @Throws(Throwable::class)
      fun accept(
        stepName: String,
        requestInfo: Rundown.StepReport.TxInfo<Request>,
        rundown: Rundown
      )
    }

    fun interface PostHook : Hook {
      @Throws(Throwable::class)
      fun accept(currentStepName: String, currentStepReport: Rundown.StepReport, rundown: Rundown)
    }
  }

  companion object {
    @JvmStatic
    fun pre(stepName: String, hook: PreHook): Set<HookConfig> =
      setOf(HookConfig(left(PRE to stepName), hook))

    @JvmStatic
    fun pre(stepNames: Set<String>, hook: PreHook): Set<HookConfig> =
      stepNames.flatMap { pre(it, hook) }.toSet()

    @JvmStatic
    fun pre(pick: PreTxnStepPick, hook: PreHook): Set<HookConfig> =
      setOf(HookConfig(right(pick), hook))

    @JvmStatic
    fun post(stepName: String, hook: PostHook): Set<HookConfig> =
      setOf(HookConfig(left(POST to stepName), hook))

    @JvmStatic
    fun post(stepNames: Set<String>, hook: PostHook): Set<HookConfig> =
      stepNames.flatMap { post(it, hook) }.toSet()

    @JvmStatic
    fun post(pick: PostTxnStepPick, hook: PostHook): Set<HookConfig> =
      setOf(HookConfig(right(pick), hook))
  }
}
