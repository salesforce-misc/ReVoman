/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.exe

import arrow.core.Either
import arrow.core.Either.Right
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.internal.postman.pm
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.ExeType.POST_HOOK
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.HookFailure.PostHookFailure
import io.github.oshai.kotlinlogging.KotlinLogging

internal fun postHookExe(
  currentStepReport: StepReport,
  kick: Kick,
  stepReports: List<StepReport>
): Either<PostHookFailure, Unit> =
  pickPostHooks(
      kick.postHooks(),
      currentStepReport,
      Rundown(stepReports, pm.environment, kick.haltOnAnyFailureExcept())
    )
    .also {
      if (it.isNotEmpty())
        logger.info { "${currentStepReport.step} Post hooks picked : ${it.size}" }
    }
    .asSequence()
    .map { postHook ->
      runChecked(currentStepReport.step, POST_HOOK) {
          postHook.accept(
            currentStepReport,
            Rundown(stepReports, pm.environment, kick.haltOnAnyFailureExcept())
          )
        }
        .mapLeft { PostHookFailure(it) }
    }
    .firstOrNull { it.isLeft() } ?: Right(Unit)

private val logger = KotlinLogging.logger {}
