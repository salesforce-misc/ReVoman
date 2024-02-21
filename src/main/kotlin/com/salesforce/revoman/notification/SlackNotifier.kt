package com.salesforce.revoman.notification

import io.github.oshai.kotlinlogging.KotlinLogging
import com.slack.api.Slack;
import com.slack.api.webhook.Payload

class SlackNotifier<T> : Notification<T> {
  companion object {
    const val SLACK_WEBHOOK_URL = "https://hooks.slack.com/services/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"
  }

  override fun notifyUser(message: T) {
    logger.info { "Sending message: ${message.toString()}" }
    val slack = Slack.getInstance()

    val response = slack.send(SLACK_WEBHOOK_URL, message as Payload);
    logger.info { "Got response: ${response.body}" }
  }
}

private val logger = KotlinLogging.logger {}
