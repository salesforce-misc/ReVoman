/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import java.math.BigDecimal
import performance.hash.Sha256
import performance.json.CanonicalJson
import tools.jackson.databind.node.JsonNodeFactory

data class ProfilerIdentity(
  val kind: String,
  val variantSha256: Sha256,
  val settingsSha256: Sha256? = null,
) {
  companion object {
    fun gc(variantSha256: Sha256): ProfilerIdentity = ProfilerIdentity("gc", variantSha256)

    fun jfr(variantSha256: Sha256, settingsSha256: Sha256): ProfilerIdentity =
      ProfilerIdentity("jfr", variantSha256, settingsSha256)
  }
}

data class DroppedSamples(val events: Long, val stackTraces: Long)

data class ProfilerAggregate(
  val category: String,
  val className: String,
  val methodName: String,
  val executionSamples: Long,
  val allocationBytes: Long,
  val lockEvents: Long,
  val ioBytes: Long,
)

data class GcCounters(
  val allocationBytesPerOperation: BigDecimal,
  val collections: BigDecimal,
  val collectionTimeMillis: BigDecimal,
)

data class GcProfilerInput(
  val captureId: String,
  val rawInputSha256: Sha256,
  val variantSha256: Sha256,
  val durationNanos: Long,
  val secondaryMetrics: Map<String, BigDecimal>,
)

enum class ProfilerSummaryFailure {
  INCOMPLETE_GC_METRICS,
}

sealed interface ProfilerSummaryBuild {
  data class Valid(val summary: ProfilerSummary, val canonicalBytes: ByteArray) :
    ProfilerSummaryBuild

  data class Invalid(val reasons: List<ProfilerSummaryFailure>) : ProfilerSummaryBuild
}

/** Privacy-safe profiler evidence shared by GC and JFR diagnostic captures. */
data class ProfilerSummary(
  val captureId: String,
  val rawInputSha256: Sha256,
  val durationNanos: Long,
  val profiler: ProfilerIdentity,
  val droppedSamples: DroppedSamples,
  val aggregates: List<ProfilerAggregate>,
  val gcCounters: GcCounters? = null,
) {
  fun canonicalBytes(): ByteArray {
    validate()
    val document = JsonNodeFactory.instance.objectNode().apply {
      set(
        "aggregates",
        JsonNodeFactory.instance.arrayNode().apply {
          aggregates
            .sortedWith(compareBy(ProfilerAggregate::category, ProfilerAggregate::className, ProfilerAggregate::methodName))
            .forEach { aggregate ->
              add(
                JsonNodeFactory.instance.objectNode().apply {
                  put("allocationBytes", aggregate.allocationBytes)
                  put("category", aggregate.category)
                  put("className", aggregate.className)
                  put("executionSamples", aggregate.executionSamples)
                  put("ioBytes", aggregate.ioBytes)
                  put("lockEvents", aggregate.lockEvents)
                  put("methodName", aggregate.methodName)
                },
              )
            }
        },
      )
      put("captureId", captureId)
      set(
        "droppedSamples",
        JsonNodeFactory.instance.objectNode().apply {
          put("events", droppedSamples.events)
          put("stackTraces", droppedSamples.stackTraces)
        },
      )
      put("durationNanos", durationNanos)
      gcCounters?.let { counters ->
        set(
          "gcCounters",
          JsonNodeFactory.instance.objectNode().apply {
            put("gc.alloc.rate.norm", counters.allocationBytesPerOperation)
            put("gc.count", counters.collections)
            put("gc.time", counters.collectionTimeMillis)
          },
        )
      }
      set(
        "profiler",
        JsonNodeFactory.instance.objectNode().apply {
          put("kind", profiler.kind)
          profiler.settingsSha256?.let { put("settingsSha256", it.hex) }
          put("variantSha256", profiler.variantSha256.hex)
        },
      )
      put("rawInputSha256", rawInputSha256.hex)
      put("schemaVersion", "profiler-summary-v1")
    }
    return CanonicalJson.encode(document)
  }

  private fun validate() {
    require(SAFE_ID.matches(captureId))
    require(durationNanos > 0)
    require(droppedSamples.events >= 0 && droppedSamples.stackTraces >= 0)
    require(aggregates.size <= 1000)
    require(profiler.kind in setOf("gc", "jfr"))
    require((profiler.kind == "jfr") == (profiler.settingsSha256 != null))
    require((profiler.kind == "gc") == (gcCounters != null))
    aggregates.forEach { aggregate ->
      require(aggregate.executionSamples >= 0)
      require(aggregate.allocationBytes >= 0)
      require(aggregate.lockEvents >= 0)
      require(aggregate.ioBytes >= 0)
      require(METHOD.matches(aggregate.methodName))
      require(CATEGORY_PATTERNS.getValue(aggregate.category).matches(aggregate.className))
    }
  }

  companion object {
    fun fromGc(input: GcProfilerInput): ProfilerSummaryBuild {
      val allocation = input.secondaryMetrics[GC_ALLOCATION]
      val collections = input.secondaryMetrics[GC_COUNT]
      val time = input.secondaryMetrics[GC_TIME]
      if (
        input.durationNanos <= 0 ||
          allocation == null || allocation < BigDecimal.ZERO ||
          collections == null || collections < BigDecimal.ZERO ||
          time == null || time < BigDecimal.ZERO
      ) {
        return ProfilerSummaryBuild.Invalid(listOf(ProfilerSummaryFailure.INCOMPLETE_GC_METRICS))
      }
      val summary =
        ProfilerSummary(
          captureId = input.captureId,
          rawInputSha256 = input.rawInputSha256,
          durationNanos = input.durationNanos,
          profiler = ProfilerIdentity.gc(input.variantSha256),
          droppedSamples = DroppedSamples(0, 0),
          aggregates = emptyList(),
          gcCounters = GcCounters(allocation, collections, time),
        )
      return ProfilerSummaryBuild.Valid(summary, summary.canonicalBytes())
    }

    private const val GC_ALLOCATION = "gc.alloc.rate.norm"
    private const val GC_COUNT = "gc.count"
    private const val GC_TIME = "gc.time"
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val METHOD = Regex("(?:[A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>)")
    private val CATEGORY_PATTERNS =
      mapOf(
        "application" to Regex("com\\.salesforce\\.revoman(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+"),
        "graal" to Regex("(?:org\\.graalvm|jdk\\.graal\\.compiler)(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+"),
        "truffle" to Regex("com\\.oracle\\.truffle(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+"),
        "okio" to Regex("okio(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+"),
        "moshi" to Regex("com\\.squareup\\.moshi(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+"),
        "http4k" to Regex("org\\.http4k(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+"),
      )
  }
}
