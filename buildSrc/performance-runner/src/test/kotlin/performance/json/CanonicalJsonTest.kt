/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.json

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.BigInteger
import performance.hash.Sha256

class CanonicalJsonTest :
  FunSpec(
    {
      test("object keys are UTF-8 sorted while decimal scale is preserved") {
        val input =
          CanonicalJson.parseStrict("""{"z":1.00,"a":{"y":2,"b":true}}""".encodeToByteArray())

        CanonicalJson.encode(input).decodeToString() shouldBe
          """{"a":{"b":true,"y":2},"z":1.00}
"""
      }

      test("unsigned UTF-8 ordering and array order are deterministic") {
        val input = CanonicalJson.parseStrict("""{"é":[3,2,1],"z":0}""".encodeToByteArray())

        CanonicalJson.encode(input).decodeToString() shouldBe """{"z":0,"é":[3,2,1]}
"""
      }

      test("integers and decimals retain arbitrary precision") {
        val input =
          CanonicalJson.parseStrict(
            """{"decimal":12345678901234567890.1200,"integer":123456789012345678901234567890}"""
              .encodeToByteArray(),
          )

        input["integer"].bigIntegerValue() shouldBe BigInteger("123456789012345678901234567890")
        input["decimal"].decimalValue() shouldBe BigDecimal("12345678901234567890.1200")
        input["decimal"].decimalValue().scale() shouldBe 4
      }

      test("duplicate keys are rejected") {
        shouldThrow<Exception> {
          CanonicalJson.parseStrict("""{"duplicate":1,"duplicate":2}""".encodeToByteArray())
        }
      }

      test("trailing tokens are rejected") {
        shouldThrow<Exception> { CanonicalJson.parseStrict("""{} []""".encodeToByteArray()) }
      }

      listOf("NaN", "+1", "01", ".5", "1.").forEach { nonStandardNumber ->
        test("non-standard number $nonStandardNumber is rejected") {
          shouldThrow<Exception> {
            CanonicalJson.parseStrict("""{"value":$nonStandardNumber}""".encodeToByteArray())
          }
        }
      }

      test("SHA-256 parsing accepts lowercase full digests only") {
        val digest = "0123456789abcdef".repeat(4)

        Sha256.parse(digest).hex shouldBe digest
        listOf(digest.uppercase(), digest.dropLast(1), "g".repeat(64)).forEach { invalid ->
          shouldThrow<IllegalArgumentException> { Sha256.parse(invalid) }
        }
      }

      test("SHA-256 digest uses the standard lowercase encoding") {
        Sha256.digest("abc".encodeToByteArray()).hex shouldBe
          "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
      }
    },
  )
