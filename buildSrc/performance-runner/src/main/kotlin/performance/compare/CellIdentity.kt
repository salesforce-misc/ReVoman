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

/** Exact comparison-cell identity and its frozen bootstrap-v1 byte encoding. */
internal data class CellIdentity(
  val benchmark: String,
  val profile: String,
  val parameters: Map<String, String>,
  val mode: String,
  val unit: String,
  val threads: Int,
  val batchSize: Int,
  val primaryMetric: String,
  val direction: String,
) {
  init {
    require(
      listOf(benchmark, profile, mode, unit, primaryMetric, direction).all(String::isNotEmpty)
    )
    require(threads > 0 && batchSize > 0)
    require(parameters.keys.all(String::isNotEmpty))
  }

  fun canonicalBytes(): ByteArray =
    ByteArrayOutputStream().use { bytes ->
      DataOutputStream(bytes).use { output ->
        output.write(CELL_DOMAIN)
        output.writeLengthPrefixed(benchmark)
        output.writeLengthPrefixed(profile)
        output.writeInt(parameters.size)
        parameters.entries.sortedWith { left, right ->
          compareUnsignedUtf8(left.key, right.key)
        }.forEach { (name, value) ->
          output.writeLengthPrefixed(name)
          output.writeLengthPrefixed(value)
        }
        output.writeLengthPrefixed(mode)
        output.writeLengthPrefixed(unit)
        output.writeInt(threads)
        output.writeInt(batchSize)
        output.writeLengthPrefixed(primaryMetric)
        output.writeLengthPrefixed(direction)
      }
      bytes.toByteArray()
    }

  private fun DataOutputStream.writeLengthPrefixed(value: String) {
    val encoded = value.encodeToByteArray()
    writeInt(encoded.size)
    write(encoded)
  }

  private fun compareUnsignedUtf8(left: String, right: String): Int {
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    val shared = minOf(leftBytes.size, rightBytes.size)
    for (index in 0 until shared) {
      val comparison =
        (leftBytes[index].toInt() and UNSIGNED_BYTE_MASK) -
          (rightBytes[index].toInt() and UNSIGNED_BYTE_MASK)
      if (comparison != 0) return comparison
    }
    return leftBytes.size - rightBytes.size
  }

  private companion object {
    val CELL_DOMAIN = "revoman-cell-v1\u0000".encodeToByteArray()
    const val UNSIGNED_BYTE_MASK = 0xff
  }
}
