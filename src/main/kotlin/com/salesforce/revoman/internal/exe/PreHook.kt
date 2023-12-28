package com.salesforce.revoman.internal.exe

import arrow.core.Either
import com.salesforce.revoman.input.config.HookConfig
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.Rundown.StepReport
import com.salesforce.revoman.output.Rundown.StepReport.HookFailure.PreHookFailure
import com.salesforce.revoman.output.Rundown.StepReport.TxInfo
import org.http4k.core.Request

internal fun preHookExe(
  stepName: String,
  kick: Kick,
  requestInfo: TxInfo<Request>,
  stepNameToReport: Map<String, StepReport>
): Either<PreHookFailure, Unit>? =
  (getHooksForStepName<HookConfig.Hook.PreHook>(
      stepName,
      kick.preHooksWithStepNamesFlattened(),
    ) +
      pickPreHooks(
        kick.preHooksWithPicksFlattened(),
        stepName,
        requestInfo,
        Rundown(stepNameToReport, pm.environment, kick.haltOnAnyFailureExceptForSteps())
      ))
    .asSequence()
    .map { preHook ->
      runChecked(stepName, StepReport.ExeType.PRE_HOOK) {
          preHook.accept(
            stepName,
            requestInfo,
            Rundown(stepNameToReport, pm.environment, kick.haltOnAnyFailureExceptForSteps())
          )
        }
        .mapLeft { PreHookFailure(it, requestInfo) }
    }
    .firstOrNull { it.isLeft() }
