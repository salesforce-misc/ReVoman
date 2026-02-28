/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
@file:JvmName("RundownJsonWriter")

package com.salesforce.revoman.output

import com.salesforce.revoman.input.json.bool
import com.salesforce.revoman.input.json.integer
import com.salesforce.revoman.input.json.listW
import com.salesforce.revoman.input.json.lng
import com.salesforce.revoman.input.json.objW
import com.salesforce.revoman.input.json.string
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.failure.ExeFailure
import com.salesforce.revoman.output.report.failure.HttpStatusUnsuccessful
import com.salesforce.revoman.output.report.failure.PollingFailure.PollingRequestFailure
import com.salesforce.revoman.output.report.failure.PollingFailure.PollingTimeoutFailure
import com.squareup.moshi.JsonWriter
import okio.Buffer
import org.http4k.core.HttpMessage

@JvmOverloads
fun Rundown.toJson(verbosity: Verbosity = Verbosity.STANDARD): String {
  val buffer = Buffer()
  JsonWriter.of(buffer).use { writer ->
    writer.indent = "  "
    writer.beginObject()
    writer.writeSummary(this)
    writer.name("firstUnsuccessfulStepReport")
    firstUnsuccessfulStepReport?.writeStepReportSummary(writer) ?: writer.nullValue()
    if (verbosity >= Verbosity.STANDARD) {
      listW("stepReports", stepReports, writer) { report ->
        report.writeStepReport(writer, verbosity)
      }
    }
    if (verbosity == Verbosity.VERBOSE) {
      writer.name("environment")
      writer.writeStringMap(immutableEnv)
    }
    writer.endObject()
  }
  return buffer.readUtf8()
}

private fun JsonWriter.writeSummary(rundown: Rundown) {
  integer("providedStepsToExecuteCount", rundown.providedStepsToExecuteCount)
  integer("executedStepCount", rundown.executedStepCount)
  integer("httpFailureStepCount", rundown.httpFailureStepCount)
  integer("unsuccessfulStepCount", rundown.unsuccessfulStepCount)
  integer("executionFailureStepCount", rundown.executionFailureStepCount)
  bool("areAllStepsSuccessful", rundown.areAllStepsSuccessful)
  bool("areAllStepsExceptIgnoredSuccessful", rundown.areAllStepsExceptIgnoredSuccessful)
}

private fun StepReport.writeStepReportSummary(writer: JsonWriter) {
  writer.beginObject()
  writer.string("index", step.index)
  writer.string("name", step.name)
  writer.bool("isSuccessful", isSuccessful)
  writer.writeFailure(this, Verbosity.SUMMARY)
  writer.endObject()
}

private fun StepReport.writeStepReport(writer: JsonWriter, verbosity: Verbosity) {
  writer.beginObject()
  writer.string("index", step.index)
  writer.string("name", step.name)
  writer.string("displayName", step.displayName)
  writer.bool("isSuccessful", isSuccessful)
  writer.bool("isHttpStatusSuccessful", isHttpStatusSuccessful)
  requestInfo?.fold({ it.requestInfo.httpMsg }, { it.httpMsg })?.let { request ->
    writer.objW("request", request) {
      writer.string("method", method.toString())
      writer.string("uri", uri.toString())
      if (verbosity == Verbosity.VERBOSE) {
        writer.writeHeaders(this)
        writer.string("body", bodyString())
      }
    }
  }
  responseInfo?.fold({ it.responseInfo.httpMsg }, { it.httpMsg })?.let { response ->
    writer.objW("response", response) {
      writer.integer("statusCode", status.code)
      writer.string("statusDescription", status.description)
      if (verbosity == Verbosity.VERBOSE) {
        writer.writeHeaders(this)
        writer.string("body", bodyString())
      }
    }
  }
  writer.writeFailure(this, verbosity)
  pollingReport?.let { pr ->
    writer.objW("pollingReport", pr) {
      writer.integer("pollAttempts", pollAttempts)
      writer.lng("totalDurationMs", totalDuration.toMillis())
      if (verbosity == Verbosity.VERBOSE) {
        writer.integer("responseCount", responses.size)
      }
    }
  }
  pollingFailure?.let { pf ->
    writer.objW("pollingFailure", pf) {
      writer.string("message", failure.message)
      when (this) {
        is PollingTimeoutFailure -> {
          writer.integer("pollAttempts", pollAttempts)
          writer.lng("timeoutMs", timeout.toMillis())
        }
        is PollingRequestFailure -> {
          writer.integer("pollAttempts", pollAttempts)
        }
      }
      if (verbosity == Verbosity.VERBOSE) {
        writer.string("stackTrace", failure.stackTraceToString())
      }
    }
  }
  if (verbosity == Verbosity.VERBOSE) {
    writer.name("envSnapshot")
    writer.writeStringMap(pmEnvSnapshot)
  }
  writer.endObject()
}

private fun JsonWriter.writeFailure(report: StepReport, verbosity: Verbosity) {
  report.failure?.fold(
    { exeFailure: ExeFailure ->
      objW("failure", exeFailure) {
        string("type", exeType.toString())
        string("message", failure.message)
        if (verbosity == Verbosity.VERBOSE) {
          string("stackTrace", failure.stackTraceToString())
        }
      }
    },
    { httpUnsuccessful: HttpStatusUnsuccessful ->
      objW("failure", httpUnsuccessful) {
        string("type", exeType.toString())
        integer("httpStatusCode", responseInfo.httpMsg.status.code)
        string("httpStatusDescription", responseInfo.httpMsg.status.description)
      }
    },
  )
}

private fun JsonWriter.writeHeaders(httpMsg: HttpMessage) {
  name("headers")
  beginObject()
  httpMsg.headers.forEach { (key, value) -> string(key, value) }
  endObject()
}

private fun JsonWriter.writeStringMap(map: Map<String, Any?>) {
  beginObject()
  map.forEach { (key, value) -> string(key, value?.toString()) }
  endObject()
}
