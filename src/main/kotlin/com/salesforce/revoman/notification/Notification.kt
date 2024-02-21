package com.salesforce.revoman.notification

interface Notification<T> {
  fun notifyUser(message: T)
}
