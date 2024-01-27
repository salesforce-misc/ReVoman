/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.StepPick.ExeStepPick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.StepReport.Companion.containsHeader
import com.salesforce.revoman.output.report.StepReport.Companion.uriPathEndsWith
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.TxnInfo.Companion.uriPathEndsWith
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.core.Request

sealed interface StepPick {
  fun interface ExeStepPick : StepPick {
    @Throws(Throwable::class) fun pick(step: Step): Boolean

    companion object PickUtils {
      // ! TODO 04/01/24 gopala.akshintala: Common logger for all the OOTB picks
      @JvmStatic
      fun withName(stepName: String) = ExeStepPick { step -> step.stepNameMatches(stepName) }

      @JvmStatic
      fun inFolder(folderPath: String) = ExeStepPick { step -> step.isInFolder(folderPath) }
    }
  }

  fun interface PreTxnStepPick : StepPick {
    @Throws(Throwable::class)
    fun pick(currentStep: Step, currentRequestInfo: TxnInfo<Request>, rundown: Rundown): Boolean

    companion object PickUtils {
      @JvmStatic
      fun beforeStepName(stepNameSubstring: String) = PreTxnStepPick { currentStep, _, _ ->
        currentStep.stepNameMatches(stepNameSubstring)
      }

      @JvmStatic
      fun beforeAllStepNames(stepNamesSubstrings: Set<String>) =
        PreTxnStepPick { currentStep, _, _ ->
          stepNamesSubstrings.any { currentStep.stepNameMatches(it) }
        }

      @JvmStatic
      fun beforeAllStepsInFolder(folderPath: String) = PreTxnStepPick { step, _, _ ->
        step.isInFolder(folderPath)
      }

      @JvmStatic
      fun beforeAllStepsContainingHeader(key: String) = PreTxnStepPick { _, requestInfo, _ ->
        requestInfo.containsHeader(key)
      }

      @JvmStatic
      fun beforeAllStepsWithURIPathEndingWith(path: String) = PreTxnStepPick { _, requestInfo, _ ->
        requestInfo.uriPathEndsWith(path)
      }
    }
  }

  fun interface PostTxnStepPick : StepPick {
    @Throws(Throwable::class) fun pick(currentStepReport: StepReport, rundown: Rundown): Boolean

    companion object PickUtils {
      @JvmStatic
      fun afterStepName(stepNameSubstring: String) = PostTxnStepPick { stepReport, _ ->
        stepReport.step.stepNameMatches(stepNameSubstring)
      }

      @JvmStatic
      fun afterAllStepNames(stepNamesSubstrings: Set<String>) = PostTxnStepPick { stepReport, _ ->
        stepNamesSubstrings.any { stepReport.step.stepNameMatches(it) }
      }

      @JvmStatic
      fun afterAllStepsInFolder(folderPath: String) = PostTxnStepPick { stepReport, _ ->
        stepReport.step.isInFolder(folderPath)
      }

      @JvmStatic
      fun afterAllStepsContainingHeader(key: String) = PostTxnStepPick { stepReport, _ ->
        stepReport.requestInfo.containsHeader(key)
      }

      @JvmStatic
      fun afterAllStepsWithURIPathEndingWith(path: String) = PostTxnStepPick { stepReport, _ ->
        stepReport.requestInfo.uriPathEndsWith(path)
      }
    }
  }
}

private val logger = KotlinLogging.logger {}
