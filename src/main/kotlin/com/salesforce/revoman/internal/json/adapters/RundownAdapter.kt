/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.adapters

import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.output.ExeType
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.StepReport
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.rawType
import java.lang.reflect.Type

class RundownAdapter(moshi: Moshi) : JsonAdapter<Rundown>() {
  private val stepReportAdapter: JsonAdapter<List<StepReport>> =
    moshi.adapter(Types.newParameterizedType(List::class.java, StepReport::class.java))
  private val postmanEnvAdapter: JsonAdapter<PostmanEnvironment<Any?>> =
    moshi.adapter(Types.newParameterizedType(PostmanEnvironment::class.java, Any::class.java))
  private val statsAdapter: JsonAdapter<Rundown.Stats> = moshi.adapter(Rundown.Stats::class.java)

  override fun fromJson(reader: JsonReader): Rundown? {
    var stepReports: List<StepReport>? = null
    var mutableEnv: PostmanEnvironment<Any?>? = null
    val haltOnFailureOfTypeExcept: Map<ExeType, PostTxnStepPick?>? = null
    var providedStepsToExecuteCount: Int? = null
    val moshiReVoman: MoshiReVoman? = null

    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "stepReports" -> stepReports = stepReportAdapter.fromJson(reader)
        "mutableEnv" -> mutableEnv = postmanEnvAdapter.fromJson(reader)
        "providedStepsToExecuteCount" -> providedStepsToExecuteCount = reader.nextInt()
        else -> reader.skipValue()
      }
    }
    reader.endObject()

    if (
      stepReports == null ||
        mutableEnv == null ||
        haltOnFailureOfTypeExcept == null ||
        providedStepsToExecuteCount == null
    ) {
      return null
    }

    return Rundown(
      stepReports = stepReports,
      mutableEnv = mutableEnv,
      haltOnFailureOfTypeExcept = haltOnFailureOfTypeExcept,
      providedStepsToExecuteCount = providedStepsToExecuteCount,
      moshiReVoman = moshiReVoman ?: MoshiReVoman.initMoshi(),
    )
  }

  override fun toJson(writer: JsonWriter, value: Rundown?) {
    if (value == null) {
      writer.nullValue()
      return
    }

    writer.beginObject()
    writer.name("stepReports")
    stepReportAdapter.toJson(writer, value.stepReports)
    writer.name("mutableEnv")
    postmanEnvAdapter.toJson(writer, value.mutableEnv)
    writer.name("stats")
    statsAdapter.toJson(writer, value.stats)
    writer.endObject()
  }

  companion object {
    @JvmStatic
    fun factory(): Factory =
      object : Factory {
        override fun create(
          type: Type,
          annotations: Set<Annotation>,
          moshi: Moshi,
        ): JsonAdapter<*>? {
          if (type.rawType != Rundown::class.java || annotations.isNotEmpty()) {
            return null
          }
          return RundownAdapter(moshi)
        }
      }
  }
}
