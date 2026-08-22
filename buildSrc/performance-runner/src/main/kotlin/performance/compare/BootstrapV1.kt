/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.compare

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlin.math.floor

internal data class ForkSamples(val measurements: List<Double>)

internal data class RatioEstimate(
  val pointRatio: Double,
  val gainPercent: Double,
  val lower95Ratio: Double,
  val upper95Ratio: Double,
)

/** Frozen deterministic fork-resampling estimator. */
internal object BootstrapV1 {
  fun estimate(
    baselineCaptureId: String,
    candidateCaptureId: String,
    cell: CellIdentity,
    baseline: List<ForkSamples>,
    candidate: List<ForkSamples>,
  ): RatioEstimate {
    require(baselineCaptureId.isNotEmpty() && candidateCaptureId.isNotEmpty())
    require(baselineCaptureId != candidateCaptureId)
    require(baseline.isNotEmpty() && candidate.isNotEmpty())
    val baselineForkMedians = baseline.map { median(it.measurements) }
    val candidateForkMedians = candidate.map { median(it.measurements) }
    val pointRatio = median(candidateForkMedians) / median(baselineForkMedians)
    require(pointRatio.isFinite() && pointRatio > 0)
    val seed = seed(baselineCaptureId, candidateCaptureId, cell)
    val ratios =
      DoubleArray(REPLICATES) { replicate ->
        val baselineResample =
          DoubleArray(baselineForkMedians.size) { draw ->
            baselineForkMedians[drawIndex(seed, replicate, BASELINE_SIDE, draw, baselineForkMedians.size)]
          }
        val candidateResample =
          DoubleArray(candidateForkMedians.size) { draw ->
            candidateForkMedians[drawIndex(seed, replicate, CANDIDATE_SIDE, draw, candidateForkMedians.size)]
          }
        median(candidateResample.asList()) / median(baselineResample.asList())
      }
    ratios.sort()
    return RatioEstimate(
      pointRatio = pointRatio,
      gainPercent = (1.0 - pointRatio) * PERCENT,
      lower95Ratio = type7(ratios, LOWER_PERCENTILE),
      upper95Ratio = type7(ratios, UPPER_PERCENTILE),
    )
  }

  internal fun seedForTesting(
    baselineCaptureId: String,
    candidateCaptureId: String,
    cell: CellIdentity,
  ): ByteArray = seed(baselineCaptureId, candidateCaptureId, cell)

  internal fun acceptedIndicesForTesting(
    baselineCaptureId: String,
    candidateCaptureId: String,
    cell: CellIdentity,
    replicate: Int,
    side: Int,
    drawCount: Int,
    populationSize: Int,
  ): List<Int> {
    val seed = seed(baselineCaptureId, candidateCaptureId, cell)
    return List(drawCount) { draw -> drawIndex(seed, replicate, side, draw, populationSize) }
  }

  internal fun medianForTesting(values: List<Double>): Double = median(values)

  private fun seed(
    baselineCaptureId: String,
    candidateCaptureId: String,
    cell: CellIdentity,
  ): ByteArray {
    val cellBytes = cell.canonicalBytes()
    val input =
      ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
          output.write(SEED_DOMAIN)
          output.writeLengthPrefixed(baselineCaptureId)
          output.writeLengthPrefixed(candidateCaptureId)
          output.writeInt(cellBytes.size)
          output.write(cellBytes)
        }
        bytes.toByteArray()
      }
    return sha256(input)
  }

  private fun drawIndex(
    seed: ByteArray,
    replicate: Int,
    side: Int,
    draw: Int,
    populationSize: Int,
  ): Int {
    require(replicate >= 0 && side in BASELINE_SIDE..CANDIDATE_SIDE && draw >= 0)
    require(populationSize > 0)
    val maximumAccepted = ULong.MAX_VALUE - ((ULong.MAX_VALUE % populationSize.toULong() + 1uL) % populationSize.toULong())
    var retry = 0L
    while (retry <= UINT32_MAX) {
      val input =
        ByteArrayOutputStream(DRAW_INPUT_BYTES).use { bytes ->
          DataOutputStream(bytes).use { output ->
            output.write(seed)
            output.writeInt(replicate)
            output.writeByte(side)
            output.writeInt(draw)
            output.writeInt(retry.toInt())
          }
          bytes.toByteArray()
        }
      val digest = sha256(input)
      var unsigned = 0uL
      repeat(Long.SIZE_BYTES) { index ->
        unsigned = (unsigned shl Byte.SIZE_BITS) or (digest[index].toInt() and 0xff).toULong()
      }
      if (unsigned <= maximumAccepted) return (unsigned % populationSize.toULong()).toInt()
      retry += 1
    }
    error("bootstrap-v1 rejection retry overflow")
  }

  private fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    require(values.all { it.isFinite() && it > 0.0 })
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
      sorted[middle]
    } else {
      sorted[middle - 1] / 2.0 + sorted[middle] / 2.0
    }
  }

  private fun type7(sorted: DoubleArray, probability: Double): Double {
    require(sorted.isNotEmpty() && probability in 0.0..1.0)
    val h = (sorted.size - 1) * probability
    val lowerIndex = floor(h).toInt()
    val fraction = h - lowerIndex
    if (lowerIndex == sorted.lastIndex) return sorted[lowerIndex]
    return sorted[lowerIndex] + fraction * (sorted[lowerIndex + 1] - sorted[lowerIndex])
  }

  private fun DataOutputStream.writeLengthPrefixed(value: String) {
    val encoded = value.encodeToByteArray()
    writeInt(encoded.size)
    write(encoded)
  }

  private fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

  private const val REPLICATES = 20_000
  private const val LOWER_PERCENTILE = 0.025
  private const val UPPER_PERCENTILE = 0.975
  private const val PERCENT = 100.0
  private const val BASELINE_SIDE = 0
  private const val CANDIDATE_SIDE = 1
  private const val DRAW_INPUT_BYTES = 32 + 4 + 1 + 4 + 4
  private const val UINT32_MAX = 0xffff_ffffL
  private val SEED_DOMAIN = "revoman-bootstrap-v1\u0000".encodeToByteArray()
}
