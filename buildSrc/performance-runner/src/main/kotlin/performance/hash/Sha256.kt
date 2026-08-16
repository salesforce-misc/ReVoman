/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.hash

import java.security.MessageDigest

/** A validated lowercase SHA-256 digest. */
@JvmInline
value class Sha256 private constructor(val hex: String) {
  override fun toString(): String = hex

  companion object {
    private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")

    /** Parses a complete lowercase hexadecimal SHA-256 digest. */
    fun parse(value: String): Sha256 {
      require(LOWERCASE_SHA256.matches(value)) { "SHA-256 must be 64 lowercase hexadecimal digits" }
      return Sha256(value)
    }

    /** Computes the SHA-256 digest of the supplied bytes. */
    fun digest(bytes: ByteArray): Sha256 =
      Sha256(
        MessageDigest.getInstance("SHA-256")
          .digest(bytes)
          .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) },
      )
  }
}
