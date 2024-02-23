package com.salesforce.revoman.notification

import com.salesforce.revoman.notification.reports.StepExecutionsResult
import com.slack.api.model.block.Blocks
import com.slack.api.model.block.HeaderBlock
import com.slack.api.model.block.SectionBlock
import com.slack.api.model.block.composition.MarkdownTextObject
import com.slack.api.model.block.composition.PlainTextObject
import com.slack.api.webhook.Payload

object PayloadBuilder {
  fun slackSummaryReportPayloadBuilder(stepExecutionsResult: StepExecutionsResult): Payload {
    val blocks = listOf(
      HeaderBlock.builder()
        .text(
          PlainTextObject.builder()
            .text(":clipboard: Steps execution report")
            .emoji(true)
            .build()
        )
        .build(),
      Blocks.divider(),
      SectionBlock.builder()
        .text(
          MarkdownTextObject.builder()
            .text("``` ---------------------- ---------- ---------- \n| Total Steps Executed | Success  | Failure |\n ====================== ========== ========== \n| ${stepExecutionsResult.totalSteps}                   | ${stepExecutionsResult.successStepsCount}        | ${stepExecutionsResult.failedStepsCount}       |\n ---------------------- ---------- ---------- ```")
            .build()
        )
        .build(),
      SectionBlock.builder()
        .text(
          MarkdownTextObject.builder()
            .text("For detailed report please click <${stepExecutionsResult.detailedReportUrl}|*here*>.")
            .build()
        )
        .build()
    )

    return Payload.builder()
      .blocks(blocks)
      .build()
  }
}
