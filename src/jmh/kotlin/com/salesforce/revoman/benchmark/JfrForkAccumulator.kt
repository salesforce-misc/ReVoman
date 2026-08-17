/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler

/**
 * Folds JMH's same-named per-fork JFR files into one ordered multi-chunk recording.
 *
 * JMH 1.37 executes forks sequentially but overwrites `profile.jfr` for every fork. This frozen
 * post-processor preserves every completed fork and advances a hash-bound marker only after the
 * aggregate and source retirement are durable.
 */
@Suppress("unused") // Loaded reflectively by JMH's frozen jfr:postProcessor option.
class JfrForkAccumulator : JavaFlightRecorderProfiler.PostProcessor {
  override fun postProcess(benchmarkParams: BenchmarkParams, jfrFile: File): List<File> {
    require(benchmarkParams.forks > 0 && benchmarkParams.warmupForks == 0)
    val source = jfrFile.toPath().toAbsolutePath().normalize()
    require(jfrFile.toPath().isAbsolute && source.fileName.toString() == JMH_RECORDING_NAME)
    val benchmarkDirectory = checkNotNull(source.parent)
    val operationRoot = checkNotNull(benchmarkDirectory.parent)
    requireDirectory(benchmarkDirectory)
    requireDirectory(operationRoot)
    val aggregate = operationRoot.resolve(AGGREGATE_NAME)
    val marker = operationRoot.resolve(MARKER_NAME)
    val markerTemporary = operationRoot.resolve(MARKER_TEMPORARY_NAME)
    val pending = operationRoot.resolve(PENDING_NAME)
    val lock = operationRoot.resolve(LOCK_NAME)

    FileChannel.open(lock, CREATE, WRITE, NOFOLLOW_LINKS).use { lockChannel ->
      lockChannel.lock().use {
        requireRegular(source)
        require(!Files.exists(pending, NOFOLLOW_LINKS))
        require(!Files.exists(markerTemporary, NOFOLLOW_LINKS))
        requireOnlyExpectedRecordings(operationRoot, source, aggregate)
        val previous = readAndVerifyState(marker, aggregate, benchmarkParams.forks)
        Files.createLink(pending, source)
        require(Files.isSameFile(pending, source))
        val sourceLength = Files.size(pending)
        require(sourceLength in 1..MAX_RECORDING_BYTES)
        require(previous.byteLength <= MAX_RECORDING_BYTES - sourceLength)
        val sourceSha256 = sha256(pending)

        if (previous.completedForks == 0) {
          Files.createLink(aggregate, pending)
          force(aggregate)
        } else {
          appendExactly(pending, aggregate, sourceLength)
        }
        require(Files.size(pending) == sourceLength && sha256(pending) == sourceSha256)
        val cumulativeLength = previous.byteLength + sourceLength
        require(Files.size(aggregate) == cumulativeLength)
        val cumulativeSha256 = sha256(aggregate)
        Files.delete(source)
        Files.delete(pending)
        fsync(benchmarkDirectory)
        fsync(operationRoot)

        val completedForks = previous.completedForks + 1
        require(completedForks <= benchmarkParams.forks)
        writeMarker(
          marker = marker,
          temporary = markerTemporary,
          state = AggregateState(completedForks, cumulativeLength, cumulativeSha256),
        )
        fsync(operationRoot)
      }
    }
    return listOf(aggregate.toFile())
  }

  private fun readAndVerifyState(
    marker: Path,
    aggregate: Path,
    expectedForks: Int,
  ): AggregateState {
    if (!Files.exists(marker, NOFOLLOW_LINKS)) {
      require(!Files.exists(aggregate, NOFOLLOW_LINKS))
      return AggregateState(0, 0, EMPTY_SHA256)
    }
    requireRegular(marker)
    requireRegular(aggregate)
    val bytes = Files.readAllBytes(marker)
    require(bytes.size in 1..MAX_MARKER_BYTES)
    val match = MARKER_PATTERN.matchEntire(bytes.toString(UTF_8)) ?: error("invalid JFR marker")
    val state =
      AggregateState(
        completedForks = match.groupValues[2].toInt(),
        byteLength = match.groupValues[1].toLong(),
        sha256 = match.groupValues[3],
      )
    require(state.completedForks in 1 until expectedForks)
    require(state.byteLength in 1..MAX_RECORDING_BYTES)
    require(Files.size(aggregate) == state.byteLength)
    require(sha256(aggregate) == state.sha256)
    return state
  }

  private fun appendExactly(source: Path, aggregate: Path, expectedLength: Long) {
    requireRegular(aggregate)
    FileChannel.open(source, READ, NOFOLLOW_LINKS).use { input ->
      FileChannel.open(aggregate, WRITE, APPEND, NOFOLLOW_LINKS).use { output ->
        val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
        var copied = 0L
        while (copied < expectedLength) {
          buffer.clear()
          buffer.limit(minOf(buffer.capacity().toLong(), expectedLength - copied).toInt())
          val read = input.read(buffer)
          require(read > 0)
          copied += read
          buffer.flip()
          while (buffer.hasRemaining()) output.write(buffer)
        }
        require(input.read(ByteBuffer.allocate(1)) == -1)
        output.force(true)
      }
    }
  }

  private fun writeMarker(marker: Path, temporary: Path, state: AggregateState) {
    val bytes =
      """{"byteLength":${state.byteLength},"completedForks":${state.completedForks},"schemaVersion":"$MARKER_VERSION","sha256":"${state.sha256}"}"""
        .plus("\n")
        .toByteArray(UTF_8)
    FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
      val buffer = ByteBuffer.wrap(bytes)
      while (buffer.hasRemaining()) channel.write(buffer)
      channel.force(true)
    }
    if (Files.exists(marker, NOFOLLOW_LINKS)) {
      requireRegular(marker)
      Files.move(temporary, marker, ATOMIC_MOVE, REPLACE_EXISTING)
    } else {
      Files.createLink(marker, temporary)
      Files.delete(temporary)
    }
  }

  private fun requireOnlyExpectedRecordings(root: Path, source: Path, aggregate: Path) {
    Files.walk(root).use { entries ->
      val paths = entries.toList()
      require(paths.none(Files::isSymbolicLink))
      val recordings = paths.filter { it.fileName.toString().endsWith(".jfr") }.toSet()
      val expected =
        if (Files.exists(aggregate, NOFOLLOW_LINKS)) setOf(source, aggregate) else setOf(source)
      require(recordings == expected)
    }
  }

  private fun requireDirectory(path: Path) {
    require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
  }

  private fun requireRegular(path: Path) {
    require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
  }

  private fun force(path: Path) {
    FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
  }

  private fun fsync(directory: Path) {
    FileChannel.open(directory, READ).use { it.force(true) }
  }

  private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
      val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
      while (channel.read(buffer) != -1) {
        buffer.flip()
        digest.update(buffer)
        buffer.clear()
      }
    }
    return HexFormat.of().formatHex(digest.digest())
  }

  private data class AggregateState(
    val completedForks: Int,
    val byteLength: Long,
    val sha256: String,
  )

  private companion object {
    const val JMH_RECORDING_NAME = "profile.jfr"
    const val AGGREGATE_NAME = "profile.jfr"
    const val MARKER_NAME = ".jfr-aggregate.json"
    const val MARKER_TEMPORARY_NAME = ".jfr-aggregate.json.tmp"
    const val PENDING_NAME = ".jfr-fork.pending.jfr"
    const val LOCK_NAME = ".jfr-aggregate.lock"
    const val MARKER_VERSION = "jfr-fork-aggregate-v1"
    const val MAX_MARKER_BYTES = 256
    const val MAX_RECORDING_BYTES = 4L * 1024 * 1024 * 1024
    const val COPY_BUFFER_BYTES = 1024 * 1024
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    val MARKER_PATTERN =
      Regex(
        """\{"byteLength":([1-9][0-9]*),"completedForks":([1-9][0-9]*),"schemaVersion":"$MARKER_VERSION","sha256":"([0-9a-f]{64})"}\n"""
      )
  }
}
