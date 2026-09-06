/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.perf

import jdk.jfr.Category
import jdk.jfr.Description
import jdk.jfr.Enabled
import jdk.jfr.Event
import jdk.jfr.EventType
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace

internal const val PERFORMANCE_EVENT_CATEGORY = "ReVoman Performance"

@Name("com.salesforce.revoman.performance.Operation")
@Label("ReVoman operation")
@Category(PERFORMANCE_EVENT_CATEGORY)
@Description("A fixed, semantically meaningful operation within ReVoman execution")
@Enabled(false)
@StackTrace(false)
@PublishedApi
internal class OperationEvent : Event() {
  @JvmField @Label("Operation") var kind: String = ""
  @JvmField @Label("Expected frequency") var frequency: String = ""
  @JvmField @Label("Completion") var completion: String = ""
  @JvmField @Label("Failure class") var failureClass: Class<*>? = null
}

@Name("com.salesforce.revoman.performance.Kick")
@Label("ReVoman kick")
@Category(PERFORMANCE_EVENT_CATEGORY)
@Description("One collection kick, including its setup, step sequence, and finalization")
@Enabled(false)
@StackTrace(false)
@PublishedApi
internal class KickEvent : Event() {
  @JvmField @Label("Collection sources") var sourceCount: Int = 0
  @JvmField @Label("Provided steps") var providedStepCount: Int = 0
  @JvmField @Label("Executed steps") var executedStepCount: Int = 0
  @JvmField @Label("Initial environment entries") var initialEnvironmentEntries: Int = 0
  @JvmField @Label("Final environment entries") var finalEnvironmentEntries: Int = 0
  @JvmField @Label("Stop reason") var stopReason: String = ""
  @JvmField @Label("Completion") var completion: String = ""
  @JvmField @Label("Failure class") var failureClass: Class<*>? = null
}

@Name("com.salesforce.revoman.performance.Step")
@Label("ReVoman step")
@Category(PERFORMANCE_EVENT_CATEGORY)
@Description("One executed, skipped, or failed ReVoman collection step")
@Enabled(false)
@StackTrace(false)
@PublishedApi
internal class StepEvent : Event() {
  @JvmField @Label("Position") var position: Int = 0
  @JvmField @Label("Iteration") var iteration: Int = 0
  @JvmField @Label("Environment entries at start") var environmentEntriesAtStart: Int = 0
  @JvmField @Label("Environment entries at end") var environmentEntriesAtEnd: Int = 0
  @JvmField @Label("Pre-request script lines") var preRequestScriptLineCount: Int = 0
  @JvmField @Label("Post-response script lines") var postResponseScriptLineCount: Int = 0
  @JvmField @Label("Pre-step hooks") var preStepHookCount: Int = 0
  @JvmField @Label("Post-step hooks") var postStepHookCount: Int = 0
  @JvmField @Label("HTTP status") var httpStatus: Int = -1
  @JvmField @Label("Request bytes") var requestBytes: Long = -1
  @JvmField @Label("Response bytes") var responseBytes: Long = -1
  @JvmField @Label("Result") var result: String = ""
  @JvmField @Label("Failure phase") var failurePhase: String = ""
  @JvmField @Label("Completion") var completion: String = ""
  @JvmField @Label("Failure class") var failureClass: Class<*>? = null
}

@PublishedApi
internal object OperationRecorder {
  @JvmField val eventType: EventType = EventType.getEventType(OperationEvent::class.java)
}

@PublishedApi
internal object KickRecorder {
  @JvmField val eventType: EventType = EventType.getEventType(KickEvent::class.java)
}

@PublishedApi
internal object StepRecorder {
  @JvmField val eventType: EventType = EventType.getEventType(StepEvent::class.java)
}
