/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
@file:JvmName("RundownJsonSerializer")

package com.salesforce.revoman.output

import com.salesforce.revoman.input.json.bool
import com.salesforce.revoman.input.json.integer
import com.salesforce.revoman.input.json.listW
import com.salesforce.revoman.input.json.lng
import com.salesforce.revoman.input.json.mapW
import com.salesforce.revoman.input.json.objW
import com.salesforce.revoman.input.json.string
import com.salesforce.revoman.output.report.StepReport
import com.squareup.moshi.JsonWriter
import okio.Buffer
import org.http4k.core.Request
import org.http4k.core.Response

/**
 * Serializes [Rundown] to JSON with configurable verbosity.
 *
 * @param verbosity Controls the level of detail in the JSON output
 * @return JSON string representation of this Rundown
 */
@JvmOverloads
fun Rundown.toJson(verbosity: RundownVerbosity = RundownVerbosity.STANDARD): String {
  val buffer = Buffer()
  val writer = JsonWriter.of(buffer)
  writer.indent = "  "

  objW(
    this,
    writer,
    { rundown ->
      // * Core metrics (always included)
      integer("providedStepsToExecuteCount", rundown.providedStepsToExecuteCount, writer)
      integer("executedStepCount", rundown.executedStepCount, writer)
      integer("httpFailureStepCount", rundown.httpFailureStepCount, writer)
      integer("unsuccessfulStepCount", rundown.unsuccessfulStepCount, writer)
      integer("executionFailureStepCount", rundown.executionFailureStepCount, writer)
      bool("areAllStepsSuccessful", rundown.areAllStepsSuccessful, writer)
      bool("areAllStepsExceptIgnoredSuccessful", rundown.areAllStepsExceptIgnoredSuccessful, writer)

      // * Environment (verbosity-dependent)
      when (verbosity) {
        RundownVerbosity.SUMMARY -> {
          writer.name("environmentKeys")
          listW(rundown.mutableEnv.keys.toList(), writer, { key -> writer.value(key) })
        }
        RundownVerbosity.STANDARD,
        RundownVerbosity.DETAILED -> {
          val anyAdapter =
            rundown.mutableEnv.moshiReVoman.adapter<Any>(Any::class.java).serializeNulls()
          writer.name("environment")
          writer.beginObject()
          writer.mapW(rundown.immutableEnv, anyAdapter)
          writer.endObject()
        }
        RundownVerbosity.FULL -> {
          val anyAdapter =
            rundown.mutableEnv.moshiReVoman.adapter<Any>(Any::class.java).serializeNulls()
          writer.name("environment")
          writer.beginObject()
          writer.mapW(rundown.mutableEnv, anyAdapter)
          writer.endObject()
        }
      }

      // * First unsuccessful step (STANDARD and above)
      if (verbosity >= RundownVerbosity.STANDARD) {
        writer.name("firstUnsuccessfulStepReport")
        rundown.firstUnsuccessfulStepReport?.let { writeStepReport(it, writer, verbosity) }
          ?: writer.nullValue()

        writer.name("firstUnIgnoredUnsuccessfulStepReport")
        rundown.firstUnIgnoredUnsuccessfulStepReport?.let { writeStepReport(it, writer, verbosity) }
          ?: writer.nullValue()
      }

      // * All step reports
      writer.name("stepReports")
      listW(
        rundown.stepReports,
        writer,
        { stepReport -> writeStepReport(stepReport, writer, verbosity) },
      )
    },
  )

  return buffer.readUtf8()
}

private fun writeStepReport(
  stepReport: StepReport,
  writer: JsonWriter,
  verbosity: RundownVerbosity,
) {
  objW(
    stepReport,
    writer,
    { report ->
      // * Step info (always included)
      objW(
        "step",
        report.step,
        writer,
        { step ->
          string("index", step.index, writer)
          string("name", step.name, writer)
          string("displayName", step.displayName, writer)
          string("path", step.path, writer)
          bool("isInRoot", step.isInRoot, writer)
          if (verbosity >= RundownVerbosity.DETAILED) {
            integer("preStepHookCount", step.preStepHookCount, writer)
            integer("postStepHookCount", step.postStepHookCount, writer)
          }
        },
      )

      // * Success/Failure status (always included)
      bool("isSuccessful", report.isSuccessful, writer)
      bool("isHttpStatusSuccessful", report.isHttpStatusSuccessful, writer)
      writer.name("exeTypeForFailure")
      report.exeTypeForFailure?.toString()?.let(writer::value) ?: writer.nullValue()

      // * Failure details (STANDARD and above)
      if (verbosity >= RundownVerbosity.STANDARD) {
        writer.name("exeFailure")
        report.exeFailure?.let { failure ->
          objW(
            failure,
            writer,
            { f ->
              string("type", f::class.simpleName, writer)
              string("message", f.failure.message, writer)
              if (verbosity >= RundownVerbosity.DETAILED) {
                string("stackTrace", f.failure.stackTraceToString(), writer)
              }
            },
          )
        } ?: writer.nullValue()

        writer.name("preStepHookFailure")
        report.preStepHookFailure?.let { failure ->
          objW(
            failure,
            writer,
            { f ->
              string("message", f.failure.message, writer)
              if (verbosity >= RundownVerbosity.DETAILED) {
                string("stackTrace", f.failure.stackTraceToString(), writer)
              }
            },
          )
        } ?: writer.nullValue()

        writer.name("postStepHookFailure")
        report.postStepHookFailure?.let { failure ->
          objW(
            failure,
            writer,
            { f ->
              string("message", f.failure.message, writer)
              if (verbosity >= RundownVerbosity.DETAILED) {
                string("stackTrace", f.failure.stackTraceToString(), writer)
              }
            },
          )
        } ?: writer.nullValue()

        writer.name("pollingFailure")
        report.pollingFailure?.let { failure ->
          objW(
            failure,
            writer,
            { f ->
              string("type", f::class.simpleName, writer)
              string("message", f.failure.message, writer)
              if (verbosity >= RundownVerbosity.DETAILED) {
                string("stackTrace", f.failure.stackTraceToString(), writer)
              }
            },
          )
        } ?: writer.nullValue()
      }

      // * Request info (DETAILED and above)
      if (verbosity >= RundownVerbosity.DETAILED) {
        writer.name("requestInfo")
        report.requestInfo?.fold(
          { failure ->
            objW(
              failure,
              writer,
              { f ->
                string("failureType", f::class.simpleName, writer)
                string("message", f.failure.message, writer)
                if (verbosity == RundownVerbosity.FULL) {
                  string("stackTrace", f.failure.stackTraceToString(), writer)
                }
              },
            )
          },
          { txnInfo ->
            objW(
              txnInfo,
              writer,
              { info ->
                bool("isJson", info.isJson, writer)
                writer.name("txnObjType")
                info.txnObjType?.typeName?.let(writer::value) ?: writer.nullValue()

                objW(
                  "httpMsg",
                  info.httpMsg,
                  writer,
                  { request -> writeRequestInfo(request, writer, verbosity) },
                )
              },
            )
          },
        ) ?: writer.nullValue()
      }

      // * Response info (DETAILED and above)
      if (verbosity >= RundownVerbosity.DETAILED) {
        writer.name("responseInfo")
        report.responseInfo?.fold(
          { failure ->
            objW(
              failure,
              writer,
              { f ->
                string("failureType", f::class.simpleName, writer)
                string("message", f.failure.message, writer)
                if (verbosity == RundownVerbosity.FULL) {
                  string("stackTrace", f.failure.stackTraceToString(), writer)
                }
              },
            )
          },
          { txnInfo ->
            objW(
              txnInfo,
              writer,
              { info ->
                bool("isJson", info.isJson, writer)
                writer.name("txnObjType")
                info.txnObjType?.typeName?.let(writer::value) ?: writer.nullValue()

                objW(
                  "httpMsg",
                  info.httpMsg,
                  writer,
                  { response -> writeResponseInfo(response, writer, verbosity) },
                )
              },
            )
          },
        ) ?: writer.nullValue()
      }

      // * Polling report (STANDARD and above)
      if (verbosity >= RundownVerbosity.STANDARD) {
        writer.name("pollingReport")
        report.pollingReport?.let { polling ->
          objW(
            polling,
            writer,
            { p ->
              integer("pollAttempts", p.pollAttempts, writer)
              lng("totalDurationMs", p.totalDuration.toMillis(), writer)
              if (verbosity >= RundownVerbosity.DETAILED) {
                writer.name("responses")
                listW(
                  p.responses,
                  writer,
                  { response -> writeResponseInfo(response, writer, verbosity) },
                )
              }
            },
          )
        } ?: writer.nullValue()
      }

      // * Execution timings (STANDARD and above)
      if (verbosity >= RundownVerbosity.STANDARD) {
        writer.name("exeTimings")
        writer.beginObject()
        report.exeTimings.forEach { (exeType, duration) ->
          lng(exeType.toString(), duration.toMillis(), writer)
        }
        writer.endObject()
      }

      // * Environment snapshot (FULL only)
      if (verbosity == RundownVerbosity.FULL) {
        val anyAdapter =
          report.pmEnvSnapshot.moshiReVoman.adapter<Any>(Any::class.java).serializeNulls()
        writer.name("pmEnvSnapshot")
        writer.beginObject()
        writer.mapW(report.pmEnvSnapshot, anyAdapter)
        writer.endObject()
      }
    },
  )
}

private fun writeRequestInfo(request: Request, writer: JsonWriter, verbosity: RundownVerbosity) {
  string("method", request.method.name, writer)
  string("uri", request.uri.toString(), writer)

  writer.name("headers")
  writer.beginObject()
  request.headers.forEach { (name, value) ->
    writer.name(name)
    writer.value(value)
  }
  writer.endObject()

  if (verbosity == RundownVerbosity.FULL) {
    string("body", request.bodyString(), writer)
  }
}

private fun writeResponseInfo(response: Response, writer: JsonWriter, verbosity: RundownVerbosity) {
  integer("statusCode", response.status.code, writer)
  string("statusDescription", response.status.description, writer)
  bool("successful", response.status.successful, writer)

  writer.name("headers")
  writer.beginObject()
  response.headers.forEach { (name, value) ->
    writer.name(name)
    writer.value(value)
  }
  writer.endObject()

  if (verbosity == RundownVerbosity.FULL) {
    string("body", response.bodyString(), writer)
  }
}
