/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PrivacyFilterTest :
  FunSpec(
    {
      test("sanitizes host paths usernames hostnames IPs environment values commands and secrets") {
        val unsafe =
          """
          user=alice host=build-host.internal ip=192.168.65.3
          path=/Users/alice/private/revoman command=/bin/sh -lc 'java -jar secret.jar'
          HOME=/Users/alice AWS_SECRET_ACCESS_KEY=topsecret BUILD_ADDRESS=2001:db8::1
          windowsPath=C:\Users\alice\private\revoman
          token=ghp_0123456789abcdefghijklmnopqrstuvwxyz
          """.trimIndent()

        val safe = PrivacyFilter().sanitize(unsafe)

        listOf(
            "alice",
            "build-host.internal",
            "192.168.65.3",
            "2001:db8::1",
            "/Users/",
            "C:\\Users\\alice",
            "AWS_SECRET_ACCESS_KEY",
            "topsecret",
            "ghp_",
            "/bin/sh",
            "secret.jar",
          )
          .forEach { unsafeValue -> safe shouldNotContain unsafeValue }
        safe shouldContain "[redacted-user]"
        safe shouldContain "[redacted-host]"
        safe shouldContain "[redacted-ip]"
        safe shouldContain "[redacted-path]"
        safe shouldContain "[redacted-command]"
        safe shouldContain "[redacted-environment]"
        safe shouldContain "[redacted-secret]"
      }

      test("does not modify benchmark names parameters modes units or measurement arrays") {
        val semantic =
          """{"benchmark":"com.salesforce.revoman.benchmark.RevUpV3WarmBenchmark.warm","mode":"ss","params":{"scenario":"v3-real-wire"},"rawData":[[1.25,1.50]],"scoreUnit":"ms/op"}"""

        PrivacyFilter().sanitize(semantic) shouldBe semantic
      }

      test("sanitizes JSON string values without changing object keys or numeric structure") {
        val unsafe =
          """{"command":"/bin/sh -lc whoami","host":"runner.internal","samples":[1.25,1.5]}"""

        val safe = PrivacyFilter().sanitizeJson(unsafe.encodeToByteArray()).decodeToString()

        safe shouldContain "\"command\":\"[redacted-command]\""
        safe shouldContain "\"host\":\"[redacted-host]\""
        safe shouldContain "\"samples\":[1.25,1.5]"
        safe shouldNotContain "runner.internal"
        safe shouldNotContain "whoami"
      }
    },
  )
