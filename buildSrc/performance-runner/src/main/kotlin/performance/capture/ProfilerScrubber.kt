/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedFrame
import jdk.jfr.consumer.RecordingFile
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind
import tools.jackson.databind.node.JsonNodeFactory

internal data class ProfilerScrubRequest(
  val captureId: String,
  val provisionalCaptureSha256: Sha256,
  val expectedRawInputSha256: Sha256,
  val variantSha256: Sha256,
  val settingsSha256: Sha256,
  val rawPath: Path,
  val summaryPath: Path,
  val intentPath: Path,
  val completionPath: Path,
)

internal enum class ProfilerScrubFailure {
  INVALID_PATHS,
  RAW_INPUT_MISSING,
  RAW_INPUT_HASH_MISMATCH,
  INVALID_RECORDING,
  SUMMARY_INVALID,
  TRANSACTION_FAILED,
}

internal sealed interface ProfilerScrubOutcome {
  data class Completed(val summary: ProfilerSummary) : ProfilerScrubOutcome

  data class Invalid(val reasons: List<ProfilerScrubFailure>) : ProfilerScrubOutcome
}

internal data class ProfilerScrubHooks(
  val beforeBinding: () -> Unit = {},
  val afterBindingBeforeOpen: (Path) -> Unit = {},
  val afterSummaryBeforeIntent: () -> Unit = {},
  val beforeRawRetirement: () -> Unit = {},
)

/** Derives and durably publishes only a bounded, privacy-safe JFR summary. */
internal class ProfilerScrubber private constructor(
  private val schemaValidator: EvidenceSchemaValidator,
  private val hooks: ProfilerScrubHooks,
) {
  constructor(
    schemaValidator: EvidenceSchemaValidator = EvidenceSchemaValidator(),
  ) : this(schemaValidator, ProfilerScrubHooks())

  internal constructor(hooks: ProfilerScrubHooks) : this(EvidenceSchemaValidator(), hooks)

  fun scrub(request: ProfilerScrubRequest): ProfilerScrubOutcome {
    if (!validPaths(request)) {
      return ProfilerScrubOutcome.Invalid(listOf(ProfilerScrubFailure.INVALID_PATHS))
    }
    if (!Files.isRegularFile(request.rawPath) || Files.isSymbolicLink(request.rawPath)) {
      return ProfilerScrubOutcome.Invalid(listOf(ProfilerScrubFailure.RAW_INPUT_MISSING))
    }
    val rawHash =
      runCatching { Sha256.digest(request.rawPath) }
        .getOrElse {
          return ProfilerScrubOutcome.Invalid(listOf(ProfilerScrubFailure.RAW_INPUT_MISSING))
        }
    if (rawHash != request.expectedRawInputSha256) {
      return ProfilerScrubOutcome.Invalid(listOf(ProfilerScrubFailure.RAW_INPUT_HASH_MISMATCH))
    }
    val derived =
      try {
        deriveSummary(request, rawHash)
      } catch (_: RawInputChangedException) {
        return ProfilerScrubOutcome.Invalid(
          listOf(ProfilerScrubFailure.RAW_INPUT_HASH_MISMATCH),
        )
      } catch (_: Exception) {
        return ProfilerScrubOutcome.Invalid(listOf(ProfilerScrubFailure.INVALID_RECORDING))
      }
    val summaryBytes = runCatching(derived.summary::canonicalBytes).getOrNull()
    if (summaryBytes == null) {
      cleanupBeforeDurableIntent(request, derived.verifiedInputPath)
      return ProfilerScrubOutcome.Invalid(listOf(ProfilerScrubFailure.SUMMARY_INVALID))
    }
    if (schemaValidator.validate(SchemaKind.PROFILER_SUMMARY, summaryBytes).isNotEmpty()) {
      cleanupBeforeDurableIntent(request, derived.verifiedInputPath)
      return ProfilerScrubOutcome.Invalid(listOf(ProfilerScrubFailure.SUMMARY_INVALID))
    }
    return try {
      persistTransaction(request, rawHash, summaryBytes, derived.verifiedInputPath)
      ProfilerScrubOutcome.Completed(derived.summary)
    } catch (_: RawInputChangedException) {
      cleanupBeforeDurableIntent(request, derived.verifiedInputPath)
      ProfilerScrubOutcome.Invalid(listOf(ProfilerScrubFailure.RAW_INPUT_HASH_MISMATCH))
    } catch (_: Exception) {
      cleanupBeforeDurableIntent(request, derived.verifiedInputPath)
      ProfilerScrubOutcome.Invalid(listOf(ProfilerScrubFailure.TRANSACTION_FAILED))
    }
  }

  private fun validPaths(request: ProfilerScrubRequest): Boolean {
    val supplied =
      listOf(request.rawPath, request.summaryPath, request.intentPath, request.completionPath)
    val paths = supplied.map { it.toAbsolutePath().normalize() }
    val parent = paths.first().parent
    return paths.distinct().size == paths.size &&
      supplied == paths &&
      paths.map(Path::getParent).distinct().size == 1 &&
      Files.isDirectory(parent, NOFOLLOW_LINKS) &&
      !Files.isSymbolicLink(parent) &&
      paths.drop(1).none { Files.exists(it, NOFOLLOW_LINKS) }
  }

  private fun deriveSummary(request: ProfilerScrubRequest, rawHash: Sha256): DerivedSummary {
    hooks.beforeBinding()
    verifyRawInput(request.rawPath, rawHash)
    val parent = request.rawPath.parent
    val boundPath = parent.resolve(".profiler-scrub-input.bound")
    val openedPath = parent.resolve(".profiler-scrub-input.open")
    if (Files.exists(boundPath, NOFOLLOW_LINKS) || Files.exists(openedPath, NOFOLLOW_LINKS)) {
      throw RawInputChangedException()
    }
    val aggregates = linkedMapOf<SymbolKey, MutableAggregate>()
    var droppedEvents = 0L
    var droppedStackTraces = 0L
    var first: java.time.Instant? = null
    var last: java.time.Instant? = null
    var eventCount = 0L
    var retainOpenedPath = false
    try {
      Files.createLink(boundPath, request.rawPath)
      hooks.afterBindingBeforeOpen(boundPath)
      RecordingFile(boundPath).use { recording ->
        Files.move(boundPath, openedPath, ATOMIC_MOVE)
        verifyRawInput(openedPath, rawHash)
        while (recording.hasMoreEvents()) {
          val event = recording.readEvent()
          eventCount += 1
          if (first == null || event.startTime < first) first = event.startTime
          if (last == null || event.endTime > last) last = event.endTime
          if (event.eventType.name == "jdk.DataLoss") droppedEvents += 1
          val contribution = contribution(event) ?: continue
          val symbol = allowedSymbol(event.stackTrace?.frames.orEmpty())
          val aggregate = symbol?.let(aggregates::get)
          when {
            symbol == null -> droppedStackTraces += 1
            aggregate != null -> aggregate.add(contribution)
            aggregates.size < MAX_AGGREGATES ->
              aggregates.getOrPut(symbol, ::MutableAggregate).add(contribution)
            else -> droppedStackTraces += 1
          }
        }
      }
      verifyRawInput(openedPath, rawHash)
      verifyRawInput(request.rawPath, rawHash)
      require(eventCount > 0)
      // A multi-fork aggregate is an ordered JFR chunk stream. Its duration is deliberately the
      // wall-clock span from the first recorded event to the last, including inter-fork gaps.
      val durationNanos =
        Duration.between(checkNotNull(first), checkNotNull(last)).toNanos().coerceAtLeast(1)
      val summary =
        ProfilerSummary(
          captureId = request.captureId,
          rawInputSha256 = rawHash,
          durationNanos = durationNanos,
          profiler = ProfilerIdentity.jfr(request.variantSha256, request.settingsSha256),
          droppedSamples = DroppedSamples(droppedEvents, droppedStackTraces),
          aggregates =
            aggregates.map { (symbol, counts) ->
              ProfilerAggregate(
                category = symbol.category,
                className = symbol.className,
                methodName = symbol.methodName,
                executionSamples = counts.executionSamples,
                allocationBytes = counts.allocationBytes,
                lockEvents = counts.lockEvents,
                ioBytes = counts.ioBytes,
              )
            },
        )
      retainOpenedPath = true
      return DerivedSummary(summary, openedPath)
    } finally {
      Files.deleteIfExists(boundPath)
      if (!retainOpenedPath) Files.deleteIfExists(openedPath)
    }
  }

  private fun verifyRawInput(path: Path, expectedHash: Sha256) {
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
      throw RawInputChangedException()
    }
    val actualHash = runCatching { Sha256.digest(path) }.getOrElse { throw RawInputChangedException() }
    if (actualHash != expectedHash) throw RawInputChangedException()
  }

  private fun contribution(event: RecordedEvent): Contribution? =
    when (event.eventType.name) {
      "jdk.ExecutionSample", "jdk.NativeMethodSample" -> Contribution(executionSamples = 1)
      "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB" ->
        Contribution(allocationBytes = event.longField("allocationSize"))
      "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait" -> Contribution(lockEvents = 1)
      "jdk.FileRead", "jdk.SocketRead" -> Contribution(ioBytes = event.longField("bytesRead"))
      "jdk.FileWrite", "jdk.SocketWrite" -> Contribution(ioBytes = event.longField("bytesWritten"))
      else -> null
    }

  private fun allowedSymbol(frames: List<RecordedFrame>): SymbolKey? =
    frames.asSequence().mapNotNull { frame ->
      val className = frame.method.type.name
      val category = ALLOWED_PREFIXES.entries.firstOrNull { className.startsWith(it.key) }?.value
      category?.let { SymbolKey(it, className, frame.method.name) }
    }.firstOrNull()

  private fun persistTransaction(
    request: ProfilerScrubRequest,
    rawHash: Sha256,
    summaryBytes: ByteArray,
    verifiedInputPath: Path,
  ) {
    val parent = request.rawPath.toAbsolutePath().normalize().parent
    val temporarySummary = parent.resolve(".${request.summaryPath.fileName}.tmp")
    writeFsynced(temporarySummary, summaryBytes)
    Files.move(temporarySummary, request.summaryPath, ATOMIC_MOVE)
    fsync(parent)
    val summaryHash = Sha256.digest(summaryBytes)
    hooks.afterSummaryBeforeIntent()
    val intentBytes =
      CanonicalJson.encode(
        JsonNodeFactory.instance.objectNode().apply {
          put("captureId", request.captureId)
          put("provisionalCaptureSha256", request.provisionalCaptureSha256.hex)
          put("rawInputSha256", rawHash.hex)
          put("schemaVersion", "profiler-scrub-intent-v1")
          put("summarySha256", summaryHash.hex)
        },
      )
    val temporaryIntent = parent.resolve(".${request.intentPath.fileName}.tmp")
    writeFsynced(temporaryIntent, intentBytes)
    Files.move(temporaryIntent, request.intentPath, ATOMIC_MOVE)
    fsync(parent)
    hooks.beforeRawRetirement()
    retireVerifiedRawInput(request.rawPath, verifiedInputPath, rawHash)
    fsync(parent)
    val completionBytes =
      CanonicalJson.encode(
        JsonNodeFactory.instance.objectNode().apply {
          put("intentSha256", Sha256.digest(intentBytes).hex)
          put("schemaVersion", "profiler-scrub-complete-v1")
          put("summarySha256", summaryHash.hex)
        },
      )
    writeFsynced(request.completionPath, completionBytes)
    fsync(parent)
  }

  /**
   * Before intent publication there is no recoverable transaction, so owned scratch is removed.
   * Once intent exists, all state is deliberately retained for Task 9's hash-validated recovery.
   */
  private fun cleanupBeforeDurableIntent(
    request: ProfilerScrubRequest,
    verifiedInputPath: Path,
  ) {
    if (Files.exists(request.intentPath, NOFOLLOW_LINKS)) return
    val parent = request.rawPath.parent
    val ownedScratch =
      listOf(
        parent.resolve(".${request.summaryPath.fileName}.tmp"),
        parent.resolve(".${request.intentPath.fileName}.tmp"),
        request.summaryPath,
      )
    ownedScratch.forEach { path -> runCatching { Files.deleteIfExists(path) } }
    runCatching {
      if (
        Files.exists(request.rawPath, NOFOLLOW_LINKS) &&
          Files.exists(verifiedInputPath, NOFOLLOW_LINKS) &&
          Files.isSameFile(request.rawPath, verifiedInputPath)
      ) {
        Files.delete(verifiedInputPath)
      }
    }
    runCatching { fsync(parent) }
  }

  private fun retireVerifiedRawInput(
    rawPath: Path,
    verifiedInputPath: Path,
    rawHash: Sha256,
  ) {
    val retirementPath = rawPath.parent.resolve(".profiler-scrub-input.retiring")
    if (Files.exists(retirementPath, NOFOLLOW_LINKS)) throw RawInputChangedException()
    try {
      Files.move(rawPath, retirementPath, ATOMIC_MOVE)
      if (!Files.isSameFile(retirementPath, verifiedInputPath)) {
        throw RawInputChangedException()
      }
      verifyRawInput(retirementPath, rawHash)
      Files.delete(retirementPath)
      Files.delete(verifiedInputPath)
    } catch (failure: RawInputChangedException) {
      if (
        Files.exists(retirementPath, NOFOLLOW_LINKS) &&
          !Files.exists(rawPath, NOFOLLOW_LINKS)
      ) {
        runCatching { Files.move(retirementPath, rawPath, ATOMIC_MOVE) }
      }
      throw failure
    }
  }

  private fun writeFsynced(path: Path, bytes: ByteArray) {
    FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
      val buffer = java.nio.ByteBuffer.wrap(bytes)
      while (buffer.hasRemaining()) channel.write(buffer)
      channel.force(true)
    }
  }

  private fun fsync(directory: Path) {
    FileChannel.open(directory, READ).use { it.force(true) }
  }

  private fun RecordedEvent.longField(name: String): Long =
    if (hasField(name)) getLong(name).coerceAtLeast(0) else 0

  private data class SymbolKey(val category: String, val className: String, val methodName: String)

  private data class DerivedSummary(
    val summary: ProfilerSummary,
    val verifiedInputPath: Path,
  )

  private class RawInputChangedException : RuntimeException()

  private data class Contribution(
    val executionSamples: Long = 0,
    val allocationBytes: Long = 0,
    val lockEvents: Long = 0,
    val ioBytes: Long = 0,
  )

  private class MutableAggregate {
    var executionSamples: Long = 0
    var allocationBytes: Long = 0
    var lockEvents: Long = 0
    var ioBytes: Long = 0

    fun add(value: Contribution) {
      executionSamples += value.executionSamples
      allocationBytes += value.allocationBytes
      lockEvents += value.lockEvents
      ioBytes += value.ioBytes
    }
  }

  private companion object {
    const val MAX_AGGREGATES = 1000
    val ALLOWED_PREFIXES =
      linkedMapOf(
        "com.salesforce.revoman." to "application",
        "org.graalvm." to "graal",
        "jdk.graal.compiler." to "graal",
        "com.oracle.truffle." to "truffle",
        "okio." to "okio",
        "com.squareup.moshi." to "moshi",
        "org.http4k." to "http4k",
      )
  }
}
