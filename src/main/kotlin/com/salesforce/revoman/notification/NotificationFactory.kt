package com.salesforce.revoman.notification

class NotificationFactory {
  fun <T> createNotifier(type: NotifierTypes): Notification<T> {
    return when (type) {
      NotifierTypes.SLACK -> SlackNotifier()
      else -> throw IllegalArgumentException("Notification type $type not supported")
    }
  }
}
