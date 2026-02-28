/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.logging

import com.salesforce.revoman.output.ExeType
import com.salesforce.revoman.output.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.ExeType.POLLING
import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.POST_STEP_HOOK
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.ExeType.PRE_STEP_HOOK
import com.salesforce.revoman.output.ExeType.UNMARSHALL_REQUEST
import com.salesforce.revoman.output.ExeType.UNMARSHALL_RESPONSE
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import java.time.Duration
import org.http4k.core.Status

internal object TuiLogger {

  // region ANSI escape codes
  private const val RESET = "\u001B[0m"
  private const val BOLD = "\u001B[1m"
  private const val DIM = "\u001B[2m"
  private const val RED = "\u001B[31m"
  private const val GREEN = "\u001B[32m"
  private const val YELLOW = "\u001B[33m"
  private const val BLUE = "\u001B[34m"
  private const val CYAN = "\u001B[36m"
  private const val BRIGHT_GREEN = "\u001B[92m"
  private const val BRIGHT_RED = "\u001B[91m"
  private const val BRIGHT_YELLOW = "\u001B[93m"
  private const val BRIGHT_CYAN = "\u001B[96m"
  // endregion

  // region Box-drawing characters
  private const val H = "\u2500" // ─ thin horizontal
  private const val HH = "\u2501" // ━ heavy horizontal
  private const val V = "\u2502" // │ thin vertical
  private const val VV = "\u2503" // ┃ heavy vertical
  private const val TL = "\u250C" // ┌ thin top-left
  private const val ML = "\u251C" // ├ thin middle-left
  private const val BL = "\u2514" // └ thin bottom-left
  private const val TLH = "\u250F" // ┏ heavy top-left
  private const val BLH = "\u2517" // ┗ heavy bottom-left
  // endregion

  private val SUB_STEP_ORDER: List<ExeType> =
    listOf(
      PRE_REQ_JS,
      UNMARSHALL_REQUEST,
      PRE_STEP_HOOK,
      HTTP_REQUEST,
      POST_RES_JS,
      UNMARSHALL_RESPONSE,
      POST_STEP_HOOK,
      POLLING,
    )

  private const val LINE_WIDTH = 66
  private const val NAME_COL_WIDTH = 25

  fun kickBanner(postmanCount: Int, httpFileCount: Int, totalSteps: Int): String {
    val sources =
      buildList {
          if (postmanCount > 0)
            add("$postmanCount Postman Collection${if (postmanCount > 1) "s" else ""}")
          if (httpFileCount > 0)
            add("$httpFileCount HTTP file${if (httpFileCount > 1) "s" else ""}")
        }
        .joinToString(" + ")
    val border = HH.repeat(LINE_WIDTH)
    return buildString {
      appendLine()
      appendLine("$CYAN$TLH$border$RESET")
      appendLine("$CYAN$VV$RESET  ${BOLD}${BRIGHT_CYAN}ReVoman$RESET")
      appendLine("$CYAN$VV$RESET  ${DIM}Sources:$RESET $sources")
      appendLine("$CYAN$VV$RESET  ${DIM}Steps:$RESET   $BOLD$totalSteps$RESET")
      append("$CYAN$BLH$border$RESET")
    }
  }

  fun stepStart(step: Step, executedIndex: Int, totalSteps: Int): String {
    val counter = formatCounter(executedIndex, totalSteps)
    val progressBar = formatProgressBar(executedIndex, totalSteps)
    val header = formatStepHeader(step)
    return "$CYAN\u25B6$RESET $counter $progressBar $DIM|$RESET $header"
  }

  fun stepReport(
    report: StepReport,
    executedIndex: Int,
    totalSteps: Int,
    haltAfter: Boolean,
  ): String {
    val httpStatus: Status? = extractHttpStatus(report)
    val requestUrl: String? = extractRequestUrl(report)
    val failedExeType: ExeType? = report.exeTypeForFailure
    val border = H.repeat(LINE_WIDTH)
    return buildString {
      appendLine()
      // Header
      val counter = formatCounter(executedIndex, totalSteps)
      val header = formatStepHeader(report.step)
      appendLine("$DIM$TL$H$RESET $counter $header $DIM${H.repeat(3)}$RESET")
      // Request URL
      if (requestUrl != null) {
        appendLine("$DIM$V$RESET  ${DIM}URL:$RESET $BLUE$requestUrl$RESET")
      }
      appendLine("$DIM$V$RESET")
      // Sub-step pipeline
      for (exeType in SUB_STEP_ORDER) {
        val duration = report.exeTimings[exeType] ?: continue
        val isFailed = exeType == failedExeType
        val statusIcon = if (isFailed) "$BRIGHT_RED\u2717$RESET" else "$BRIGHT_GREEN\u2713$RESET"
        val name = exeType.toString()
        val dotCount = maxOf(1, NAME_COL_WIDTH - name.length)
        val dots = "$DIM${"\u00B7".repeat(dotCount)}$RESET"
        val durationStr = formatDuration(duration).padStart(8)
        append("$DIM$V$RESET  $name $dots $statusIcon $BLUE$durationStr$RESET")
        if (exeType == HTTP_REQUEST && httpStatus != null) {
          val statusColor = if (httpStatus.successful) GREEN else BRIGHT_YELLOW
          append(
            "   $DIM\u2192$RESET $statusColor${httpStatus.code} ${httpStatus.description}$RESET"
          )
        }
        appendLine()
      }
      // Polling success info
      report.pollingReport?.let {
        appendLine(
          "$DIM$V$RESET  ${GREEN}Polling:$RESET ${it.pollAttempts} attempts, ${formatDuration(it.totalDuration)}"
        )
      }
      appendLine("$DIM$V$RESET")
      // Footer separator
      appendLine("$DIM$ML$border$RESET")
      // Status line
      val totalDuration = report.exeTimings.values.fold(Duration.ZERO, Duration::plus)
      val statusText =
        when {
          report.isSuccessful -> "$BRIGHT_GREEN\u2705 SUCCESS$RESET"
          report.exeFailure == null ->
            "$BRIGHT_YELLOW\u26A0\uFE0F  HTTP ${httpStatus?.code ?: "???"}$RESET"
          else -> "$BRIGHT_RED\u274C FAILED$RESET ${DIM}at $failedExeType$RESET"
        }
      appendLine(
        "$DIM$V$RESET  $statusText  $DIM\u00B7$RESET  ${DIM}Total:$RESET $BOLD${formatDuration(totalDuration)}$RESET"
      )
      // Error message
      report.exeFailure?.let {
        val msg = it.failure.message ?: it.failure.toString()
        appendLine("$DIM$V$RESET  ${RED}$msg$RESET")
      }
      // Halt/continue indicator for failed steps
      if (!report.isSuccessful) {
        if (haltAfter) {
          appendLine("$DIM$V$RESET  ${RED}\uD83D\uDED1 Halting execution$RESET")
        } else {
          appendLine("$DIM$V$RESET  ${YELLOW}\uD83D\uDEDD Continuing despite failure$RESET")
        }
      }
      // Bottom border
      append("$DIM$BL$border$RESET")
    }
  }

  fun skippedStep(step: Step): String = "$DIM\u23ED ${step.displayName} $H SKIPPED$RESET"

  fun rundownSummary(rundown: Rundown): String {
    val total = rundown.providedStepsToExecuteCount
    val executed = rundown.executedStepCount
    val skipped = total - executed
    val successful = executed - rundown.unsuccessfulStepCount
    val failed = rundown.unsuccessfulStepCount
    val successRate = if (executed > 0) (successful * 100) / executed else 0
    val titleText = " RUNDOWN SUMMARY "
    val sideLen = (LINE_WIDTH - titleText.length) / 2
    val titleLine =
      HH.repeat(sideLen) + titleText + HH.repeat(LINE_WIDTH - sideLen - titleText.length)
    val barWidth = 30
    val filledCount = if (executed > 0) (successful * barWidth) / executed else 0
    val barColor =
      when {
        successRate >= 100 -> BRIGHT_GREEN
        successRate >= 75 -> GREEN
        successRate >= 50 -> YELLOW
        else -> RED
      }
    val bar =
      "$barColor${"\u2588".repeat(filledCount)}$DIM${"\u2591".repeat(barWidth - filledCount)}$RESET"
    val border = HH.repeat(LINE_WIDTH)
    return buildString {
      appendLine()
      appendLine("$CYAN$TLH$titleLine$RESET")
      appendLine("$CYAN$VV$RESET")
      appendLine("$CYAN$VV$RESET  ${DIM}Provided Steps:$RESET  $BOLD$total$RESET")
      appendLine("$CYAN$VV$RESET  ${DIM}Executed:$RESET        $BOLD$executed$RESET")
      if (skipped > 0) {
        appendLine("$CYAN$VV$RESET  ${DIM}Skipped:$RESET         $YELLOW$skipped$RESET")
      }
      appendLine("$CYAN$VV$RESET  ${BRIGHT_GREEN}Successful:$RESET      $BOLD$successful$RESET")
      if (failed > 0) {
        appendLine("$CYAN$VV$RESET  ${BRIGHT_RED}Failed:$RESET          $BOLD$RED$failed$RESET")
      }
      appendLine("$CYAN$VV$RESET")
      appendLine("$CYAN$VV$RESET  $bar  ${BOLD}$successRate%$RESET ${DIM}success rate$RESET")
      appendLine("$CYAN$VV$RESET")
      append("$CYAN$BLH$border$RESET")
    }
  }

  private fun formatCounter(executedIndex: Int, totalSteps: Int): String =
    "$DIM[$executedIndex/$totalSteps]$RESET"

  private fun formatProgressBar(executedIndex: Int, totalSteps: Int): String {
    val barWidth = 20
    val percentage = if (totalSteps > 0) (executedIndex * 100) / totalSteps else 0
    val filledCount = if (totalSteps > 0) (executedIndex * barWidth) / totalSteps else 0
    val emptyCount = barWidth - filledCount
    val barColor =
      when {
        percentage >= 100 -> BRIGHT_GREEN
        percentage >= 75 -> GREEN
        percentage >= 50 -> CYAN
        percentage >= 25 -> YELLOW
        else -> DIM
      }
    return "$barColor${"\u2588".repeat(filledCount)}$DIM${"\u2591".repeat(emptyCount)}$RESET ${DIM}${percentage}%$RESET"
  }

  private fun formatStepHeader(step: Step): String {
    val method = step.rawPMStep.httpMethod.uppercase().ifEmpty { "???" }
    val methodColor =
      when (method) {
        "GET" -> GREEN
        "POST" -> YELLOW
        "PUT",
        "PATCH" -> BLUE
        "DELETE" -> RED
        else -> CYAN
      }
    val cleanPath = formatPath(step)
    return "$methodColor$BOLD$method$RESET $DIM~~>$RESET $BRIGHT_CYAN$cleanPath$RESET"
  }

  private fun formatPath(step: Step): String {
    val folder = step.parentFolder?.path?.joinToString(" / ") { it.name }
    return if (folder != null) "$folder / ${step.name}" else step.name
  }

  private fun extractHttpStatus(report: StepReport): Status? {
    val responseInfo = report.responseInfo ?: return null
    return when {
      responseInfo.isRight -> responseInfo.get().httpMsg.status
      else -> responseInfo.left.responseInfo.httpMsg.status
    }
  }

  private fun extractRequestUrl(report: StepReport): String? {
    val requestInfo = report.requestInfo ?: return null
    return when {
      requestInfo.isRight -> requestInfo.get().httpMsg.uri.toString()
      else -> requestInfo.left.requestInfo.httpMsg.uri.toString()
    }
  }

  private fun formatDuration(duration: Duration): String =
    when {
      duration.toMillis() < 1 -> "<1ms"
      duration.toMillis() < 1000 -> "${duration.toMillis()}ms"
      duration.seconds < 60 -> "%.1fs".format(duration.toMillis() / 1000.0)
      else -> "${duration.toMinutes()}m ${duration.seconds % 60}s"
    }
}
