/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.logging

import com.github.ajalt.mordant.animation.progress.ThreadProgressTaskAnimator
import com.github.ajalt.mordant.animation.progress.advance
import com.github.ajalt.mordant.animation.progress.animateOnThread
import com.github.ajalt.mordant.animation.progress.execute
import com.github.ajalt.mordant.rendering.TextColors.brightCyan
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Spinner
import com.github.ajalt.mordant.widgets.progress.marquee
import com.github.ajalt.mordant.widgets.progress.percentage
import com.github.ajalt.mordant.widgets.progress.progressBar
import com.github.ajalt.mordant.widgets.progress.progressBarContextLayout
import com.github.ajalt.mordant.widgets.progress.spinner
import com.github.ajalt.mordant.widgets.progress.text
import com.github.ajalt.mordant.widgets.progress.timeElapsed
import com.salesforce.revoman.output.ExeType
import com.salesforce.revoman.output.report.Step
import java.util.concurrent.Future

internal class MordantTui(totalSteps: Int) {

  private val terminal = Terminal()

  private data class StepContext(
    val stepLabel: String = "Initializing...",
    val subStep: String = "",
  )

  private val animation: ThreadProgressTaskAnimator<StepContext> =
    progressBarContextLayout<StepContext> {
        spinner(Spinner.Dots(brightCyan))
        marquee(width = 40) { context.stepLabel }
        text { context.subStep }
        progressBar()
        percentage()
        timeElapsed()
      }
      .animateOnThread(
        terminal,
        context = StepContext(),
        total = totalSteps.toLong(),
        clearWhenFinished = true,
      )

  private var future: Future<*>? = null

  fun start() {
    future = animation.execute()
  }

  fun onStepStart(step: Step, executedIndex: Int, totalSteps: Int) {
    val method = step.rawPMStep.httpMethod.uppercase().ifEmpty { "???" }
    val path =
      step.parentFolder?.path?.joinToString(" / ") { it.name }?.let { "$it / ${step.name}" }
        ?: step.name
    animation.update {
      context = StepContext(stepLabel = "[$executedIndex/$totalSteps] $method ~~> $path")
    }
  }

  fun onSubStepStart(exeType: ExeType) {
    animation.update { context = context.copy(subStep = exeType.toString()) }
  }

  fun onStepComplete() {
    animation.advance(1)
  }

  fun println(text: String) {
    terminal.println(text)
  }

  fun finish() {
    animation.update { completed = total ?: completed }
    future?.get()
  }
}
