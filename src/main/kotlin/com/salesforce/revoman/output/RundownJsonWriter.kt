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
    objW(this, writer) {
      writeSummary(writer, this)
      writer.name("firstUnsuccessfulStepReport")
      firstUnsuccessfulStepReport?.writeStepReportSummary(writer) ?: writer.nullValue()
      if (verbosity >= Verbosity.STANDARD) {
        listW("stepReports", stepReports, writer) { report ->
          report.writeStepReport(writer, verbosity)
        }
      }
      if (verbosity == Verbosity.VERBOSE) {
        writer.name("environment")
        writeStringMap(writer, immutableEnv)
      }
    }
  }
  return buffer.readUtf8()
}

private fun writeSummary(writer: JsonWriter, rundown: Rundown) {
  integer("providedStepsToExecuteCount", rundown.providedStepsToExecuteCount, writer)
  integer("executedStepCount", rundown.executedStepCount, writer)
  integer("httpFailureStepCount", rundown.httpFailureStepCount, writer)
  integer("unsuccessfulStepCount", rundown.unsuccessfulStepCount, writer)
  integer("executionFailureStepCount", rundown.executionFailureStepCount, writer)
  bool("areAllStepsSuccessful", rundown.areAllStepsSuccessful, writer)
  bool("areAllStepsExceptIgnoredSuccessful", rundown.areAllStepsExceptIgnoredSuccessful, writer)
}

private fun StepReport.writeStepReportSummary(writer: JsonWriter) {
  objW(this, writer) {
    string("index", step.index, writer)
    string("name", step.name, writer)
    bool("isSuccessful", isSuccessful, writer)
    writeFailure(writer, this, Verbosity.SUMMARY)
  }
}

private fun StepReport.writeStepReport(writer: JsonWriter, verbosity: Verbosity) {
  objW(this, writer) {
    string("index", step.index, writer)
    string("name", step.name, writer)
    string("displayName", step.displayName, writer)
    bool("isSuccessful", isSuccessful, writer)
    bool("isHttpStatusSuccessful", isHttpStatusSuccessful, writer)
    writeRequestInfo(writer, verbosity)
    writeResponseInfo(writer, verbosity)
    writeFailure(writer, this, verbosity)
    writePollingReport(writer, verbosity)
    writePollingFailure(writer, verbosity)
    if (verbosity == Verbosity.VERBOSE) {
      writer.name("envSnapshot")
      writeStringMap(writer, pmEnvSnapshot)
    }
  }
}

private fun StepReport.writeRequestInfo(writer: JsonWriter, verbosity: Verbosity) {
  requestInfo?.fold({ it.requestInfo.httpMsg }, { it.httpMsg })?.let {
    objW("request", it, writer) { req ->
      string("method", req.method.toString(), writer)
      string("uri", req.uri.toString(), writer)
      if (verbosity == Verbosity.VERBOSE) {
        writeHeaders(writer, req)
        string("body", req.bodyString(), writer)
      }
    }
  }
}

private fun StepReport.writeResponseInfo(writer: JsonWriter, verbosity: Verbosity) {
  responseInfo?.fold({ it.responseInfo.httpMsg }, { it.httpMsg })?.let {
    objW("response", it, writer) { res ->
      integer("statusCode", res.status.code, writer)
      string("statusDescription", res.status.description, writer)
      if (verbosity == Verbosity.VERBOSE) {
        writeHeaders(writer, res)
        string("body", res.bodyString(), writer)
      }
    }
  }
}

private fun StepReport.writePollingReport(writer: JsonWriter, verbosity: Verbosity) {
  pollingReport?.let {
    objW("pollingReport", it, writer) { pr ->
      integer("pollAttempts", pr.pollAttempts, writer)
      lng("totalDurationMs", pr.totalDuration.toMillis(), writer)
      if (verbosity == Verbosity.VERBOSE) {
        integer("responseCount", pr.responses.size, writer)
      }
    }
  }
}

private fun StepReport.writePollingFailure(writer: JsonWriter, verbosity: Verbosity) {
  pollingFailure?.let {
    objW("pollingFailure", it, writer) { pf ->
      string("message", pf.failure.message, writer)
      when (pf) {
        is PollingTimeoutFailure -> {
          integer("pollAttempts", pf.pollAttempts, writer)
          lng("timeoutMs", pf.timeout.toMillis(), writer)
        }
        is PollingRequestFailure -> {
          integer("pollAttempts", pf.pollAttempts, writer)
        }
      }
      if (verbosity == Verbosity.VERBOSE) {
        string("stackTrace", pf.failure.stackTraceToString(), writer)
      }
    }
  }
}

private fun writeFailure(writer: JsonWriter, report: StepReport, verbosity: Verbosity) {
  report.failure?.fold(
    { exeFailure: ExeFailure ->
      objW("failure", exeFailure, writer) { ef ->
        string("type", ef.exeType.toString(), writer)
        string("message", ef.failure.message, writer)
        if (verbosity == Verbosity.VERBOSE) {
          string("stackTrace", ef.failure.stackTraceToString(), writer)
        }
      }
    },
    { httpUnsuccessful: HttpStatusUnsuccessful ->
      objW("failure", httpUnsuccessful, writer) { hu ->
        string("type", hu.exeType.toString(), writer)
        integer("httpStatusCode", hu.responseInfo.httpMsg.status.code, writer)
        string("httpStatusDescription", hu.responseInfo.httpMsg.status.description, writer)
      }
    },
  )
}

private fun writeHeaders(writer: JsonWriter, httpMsg: HttpMessage) {
  objW("headers", httpMsg, writer) { msg ->
    msg.headers.forEach { (key, value) -> string(key, value, writer) }
  }
}

private fun writeStringMap(writer: JsonWriter, map: Map<String, Any?>) {
  objW(map, writer) { m -> m.forEach { (key, value) -> string(key, value?.toString(), writer) } }
}
