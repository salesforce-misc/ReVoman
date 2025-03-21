/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.StepReport.Companion.containsHeader
import com.salesforce.revoman.output.report.StepReport.Companion.uriPathContains
import com.salesforce.revoman.output.report.StepReport.Companion.uriPathEndsWith
import com.salesforce.revoman.output.report.TxnInfo
import com.salesforce.revoman.output.report.TxnInfo.Companion.uriPathContains
import com.salesforce.revoman.output.report.TxnInfo.Companion.uriPathEndsWith
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
      /**
       * If there are steps with the same name, you may pass the stepName along with the folderPath,
       * where each folder is seperated by
       * [FOLDER_DELIMITER][com.salesforce.revoman.output.report.Folder.FOLDER_DELIMITER]. You may
       * not need to pass the entire path, you can pass the path from the Least common Ancestor to
       * uniquely identify the step of your interest to add this Hook
       */
      @JvmStatic
      fun beforeStepName(vararg stepNameSubstrings: String) = PreTxnStepPick { currentStep, _, _ ->
        stepNameSubstrings.any { currentStep.stepNameMatches(it) }
      }

      @JvmStatic
      fun beforeEachStepInFolder(folderPath: String) = PreTxnStepPick { step, _, _ ->
        step.isInFolder(folderPath)
      }

      @JvmStatic
      fun beforeStepContainingHeader(key: String) = PreTxnStepPick { _, requestInfo, _ ->
        requestInfo.containsHeader(key)
      }

      /**
       * Provide either a full URI path or a partial URI path that can uniquely identify the request
       */
      @JvmStatic
      fun beforeStepContainingURIPathOfAny(vararg paths: String) =
        PreTxnStepPick { _, requestInfo, _ ->
          paths.any { requestInfo.uriPathContains(it) }
        }

      @JvmStatic
      fun beforeStepEndingWithURIPathOfAny(vararg paths: String) =
        PreTxnStepPick { _, requestInfo, _ ->
          paths.any { requestInfo.uriPathEndsWith(it) }
        }
    }
  }

  fun interface PostTxnStepPick : StepPick {
    @Throws(Throwable::class) fun pick(currentStepReport: StepReport, rundown: Rundown): Boolean

    companion object PickUtils {
      /**
       * If there are steps with the same name, you may pass the stepName along with the folderPath,
       * where each folder is seperated by FOLDER_DELIMITER {@see
       * com.salesforce.revoman.output.report.Folder.FOLDER_DELIMITER} You may not have to pass the
       * entire path, you can pass the path from the Least common Ancestor to uniquely identify the
       * step of your interest to add this Hook
       */
      @JvmStatic
      fun afterStepName(vararg stepNameSubstrings: String) = PostTxnStepPick { stepReport, _ ->
        stepNameSubstrings.any { stepReport.step.stepNameMatches(it) }
      }

      @JvmStatic
      fun afterEachStepInFolder(folderPath: String) = PostTxnStepPick { stepReport, _ ->
        stepReport.step.isInFolder(folderPath)
      }

      @JvmStatic
      fun afterStepContainingHeader(key: String) = PostTxnStepPick { stepReport, _ ->
        stepReport.requestInfo.containsHeader(key)
      }

      /**
       * Provide either a full URI path or a partial URI path that can uniquely identify the request
       */
      @JvmStatic
      fun afterStepContainingURIPathOfAny(vararg paths: String) = PostTxnStepPick { stepReport, _ ->
        paths.any { stepReport.requestInfo.uriPathContains(it) }
      }

      @JvmStatic
      fun afterStepEndingWithURIPathOfAny(vararg paths: String) = PostTxnStepPick { stepReport, _ ->
        paths.any { stepReport.requestInfo.uriPathEndsWith(it) }
      }
    }
  }
}
