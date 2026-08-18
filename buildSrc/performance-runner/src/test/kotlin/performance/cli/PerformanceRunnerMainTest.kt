/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import performance.runner.RunnerDependencies
import performance.runner.RunnerExit

class PerformanceRunnerMainTest :
  FunSpec(
    {
      test("invalid command returns without terminating the JVM and sanitizes standard error") {
        val privateValue = "/private/customer/repository"
        val standardError = mutableListOf<String>()

        val exit =
          runMain(
            args = listOf("not-a-command", privateValue),
            dependencies =
              RunnerDependencies(writeStandardError = { message -> standardError += message }),
          )

        exit shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID.code
        standardError shouldContainExactly
          listOf("performance-runner: INPUT_OR_PREFLIGHT_INVALID: INVALID_COMMAND")
        standardError.joinToString("\n") shouldNotContain privateValue
      }

      test("validate-distribution invokes the frozen validation boundary") {
        val standardError = mutableListOf<String>()
        val validated = mutableListOf<String>()

        val exit =
          runMain(
            args = listOf("validate-distribution", "--distribution", "distribution"),
            dependencies =
              RunnerDependencies(
                writeStandardError = { message -> standardError += message },
                validateDistribution = { path -> validated += path; true },
              ),
          )

        exit shouldBe RunnerExit.SUCCESS.code
        validated shouldContainExactly listOf("distribution")
        standardError shouldContainExactly emptyList()
      }

      mapOf(
          "capture" to listOf("capture", "--profile", "cold"),
          "compare" to listOf("compare", "--kind", "calibration"),
          "campaign" to listOf("campaign", "--profile", "warm"),
        )
        .forEach { (commandName, arguments) ->
          test("$commandName rejects an incomplete operation request without terminating the JVM") {
            val standardError = mutableListOf<String>()

            val exit =
              runMain(
                args = arguments,
                dependencies =
                  RunnerDependencies(writeStandardError = { message -> standardError += message }),
              )

            exit shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID.code
            standardError shouldContainExactly
              listOf("performance-runner: INPUT_OR_PREFLIGHT_INVALID: INVALID_ARGUMENTS")
          }
        }

      listOf(
          "scrub-profiler",
          "finalize-diagnostic",
          "finalize-standalone-comparison",
          "finalize-campaign",
          "recover",
        )
        .forEach { commandName ->
          test("$commandName rejects missing required arguments before dispatch") {
            val standardError = mutableListOf<String>()

            val exit =
              runMain(
                args = listOf(commandName),
                dependencies =
                  RunnerDependencies(writeStandardError = { message -> standardError += message }),
              )

            exit shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID.code
            standardError shouldContainExactly
              listOf("performance-runner: INPUT_OR_PREFLIGHT_INVALID: INVALID_ARGUMENTS")
          }
        }

      mapOf(
        "unknown flags" to listOf("capture", "--private-token", "do-not-print-this"),
        "duplicate flags" to listOf("capture", "--profile", "cold", "--profile", "warm"),
        "missing flag values" to listOf("capture", "--profile"),
        "raw absolute output paths" to
          listOf("capture", "--output", "/private/customer/performance-output"),
      )
        .forEach { (caseName, arguments) ->
          test("$caseName are rejected without echoing arguments") {
            val standardError = mutableListOf<String>()

            val exit =
              runMain(
                args = arguments,
                dependencies =
                  RunnerDependencies(writeStandardError = { message -> standardError += message }),
              )

            exit shouldBe RunnerExit.INPUT_OR_PREFLIGHT_INVALID.code
            standardError shouldContainExactly
              listOf("performance-runner: INPUT_OR_PREFLIGHT_INVALID: INVALID_ARGUMENTS")
            arguments.forEach { argument ->
              standardError.joinToString("\n") shouldNotContain argument
            }
          }
        }
    },
  )
