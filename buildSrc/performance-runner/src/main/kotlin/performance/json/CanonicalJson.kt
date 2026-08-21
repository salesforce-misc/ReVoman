/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.json

import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** Strict parsing and deterministic UTF-8 encoding for performance evidence JSON. */
object CanonicalJson {
  private val mapper: JsonMapper =
    JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
      .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
      .disable(
        JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS,
        JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS,
        JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS,
        JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS,
        JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS,
      )
      .build()

  /** Parses exactly one standards-compliant JSON value without losing numeric precision. */
  fun parseStrict(bytes: ByteArray): JsonNode =
    requireNotNull(mapper.readTree(bytes)) { "JSON input must contain one value" }

  /** Encodes a JSON value with unsigned UTF-8 object ordering and exactly one final newline. */
  fun encode(value: JsonNode): ByteArray = mapper.writeValueAsBytes(canonicalize(value)) + NEWLINE

  private fun canonicalize(value: JsonNode): JsonNode =
    when {
      value.isObject -> canonicalObject(value.asObject())
      value.isArray -> canonicalArray(value.asArray())
      else -> value
    }

  private fun canonicalObject(value: ObjectNode): ObjectNode =
    JsonNodeFactory.instance.objectNode().also { canonical ->
      value
        .properties()
        .sortedWith { left, right -> compareUnsignedUtf8(left.key, right.key) }
        .forEach { (name, child) -> canonical.set(name, canonicalize(child)) }
    }

  private fun canonicalArray(value: ArrayNode): ArrayNode =
    JsonNodeFactory.instance.arrayNode().also { canonical ->
      value.forEach { child -> canonical.add(canonicalize(child)) }
    }

  private fun compareUnsignedUtf8(left: String, right: String): Int {
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    val sharedLength = minOf(leftBytes.size, rightBytes.size)
    val differingIndex = (0 until sharedLength).firstOrNull { leftBytes[it] != rightBytes[it] }
    return differingIndex?.let { index ->
      (leftBytes[index].toInt() and UNSIGNED_BYTE_MASK) -
        (rightBytes[index].toInt() and UNSIGNED_BYTE_MASK)
    } ?: (leftBytes.size - rightBytes.size)
  }

  private const val UNSIGNED_BYTE_MASK = 0xff
  private val NEWLINE = byteArrayOf('\n'.code.toByte())
}
