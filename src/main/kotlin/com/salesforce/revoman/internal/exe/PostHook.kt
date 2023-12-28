package com.salesforce.revoman.internal.exe

import arrow.core.Either
import com.salesforce.revoman.input.config.HookConfig.Hook.PostHook
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.ExeType
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.HookFailure.PostHookFailure

internal fun postHookExe(
  stepReport: StepReport,
  kick: Kick,
  stepReports: List<StepReport>
): Either<PostHookFailure, Unit>? {
  val currentStepName = stepReport.stepName
  return (getHooksForStepName<PostHook>(
      currentStepName,
      kick.postHooksWithStepNamesFlattened(),
    ) +
      pickPostHooks(
        kick.postHooksWithPicksFlattened(),
        stepReport,
        Rundown(stepReports, pm.environment, kick.haltOnAnyFailureExceptForSteps())
      ))
    .asSequence()
    .map { postHook ->
      runChecked(currentStepName, ExeType.POST_HOOK) {
          postHook.accept(
            stepReport,
            Rundown(stepReports, pm.environment, kick.haltOnAnyFailureExceptForSteps())
          )
        }
        .mapLeft { PostHookFailure(it) }
    }
    .firstOrNull { it.isLeft() }
}
