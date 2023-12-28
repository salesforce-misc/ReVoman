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
  stepName: String,
  kick: Kick,
  stepNameToReport: Map<String, StepReport>
): Either<PostHookFailure, Unit>? =
  (getHooksForStepName<PostHook>(
      stepName,
      kick.postHooksWithStepNamesFlattened(),
    ) +
      pickPostHooks(
        kick.postHooksWithPicksFlattened(),
        stepName,
        Rundown(stepNameToReport, pm.environment, kick.haltOnAnyFailureExceptForSteps())
      ))
    .asSequence()
    .map { postHook ->
      runChecked(stepName, ExeType.POST_HOOK) {
          postHook.accept(
            stepName,
            stepNameToReport[stepName]!!,
            Rundown(stepNameToReport, pm.environment, kick.haltOnAnyFailureExceptForSteps())
          )
        }
        .mapLeft { PostHookFailure(it) }
    }
    .firstOrNull { it.isLeft() }
