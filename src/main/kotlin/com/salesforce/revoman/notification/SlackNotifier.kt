package com.salesforce.revoman.notification

import io.github.oshai.kotlinlogging.KotlinLogging
import com.slack.api.Slack;
import com.slack.api.webhook.Payload

class SlackNotifier<T> : Notification<T> {
  companion object {
    val SLACK_WEBHOOK_URL: String = System.getenv("SLACK_WEBHOOK_URL");
  }

  override fun notifyUser(message: T) {
    logger.info { "Sending message: ${message.toString()}" }
    val slack = Slack.getInstance()
    if (SLACK_WEBHOOK_URL.isBlank()) {
      logger.error { "Failed to send message to Slack webhook $SLACK_WEBHOOK_URL" }
      return
    }
    
    val response = slack.send(SLACK_WEBHOOK_URL, message as Payload);
    logger.info { "Got response: ${response.body}" }
  }
}

private val logger = KotlinLogging.logger {}
