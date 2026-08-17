/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.runner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class RunnerExitContractTest :
  FunSpec(
    {
      test("stable process exit table") {
        RunnerExit.entries.associateWith(RunnerExit::code) shouldBe
          mapOf(
            RunnerExit.SUCCESS to 0,
            RunnerExit.INPUT_OR_PREFLIGHT_INVALID to 2,
            RunnerExit.MEASUREMENT_INVALID to 3,
            RunnerExit.INCOMPATIBLE to 4,
            RunnerExit.CALIBRATION_FAILED to 5,
            RunnerExit.POLICY_FAILED to 6,
            RunnerExit.POLICY_INCONCLUSIVE to 7,
            RunnerExit.INTERNAL_OR_PUBLICATION_FAILED to 8,
          )
      }

      test("invalid outcomes never exit zero") {
        RunnerExit.entries.filterNot { it == RunnerExit.SUCCESS }.map { it.code shouldNotBe 0 }
      }

      test("distribution validation returns success only through the frozen validator boundary") {
        val validated = mutableListOf<String>()
        val standardError = mutableListOf<String>()
        val engine =
          RunnerEngine(
            RunnerDependencies(
              writeStandardError = { message -> standardError += message },
              validateDistribution = { path -> validated += path; path == "valid-distribution" },
            ),
          )

        engine.execute(
          RunnerCommand.ValidateDistribution(
            listOf("--distribution", "valid-distribution"),
          ),
        ) shouldBe RunnerOutcome(RunnerExit.SUCCESS, publishedArtifact = null)
        engine.execute(
          RunnerCommand.ValidateDistribution(
            listOf("--distribution", "private-invalid-path"),
          ),
        ) shouldBe RunnerOutcome(RunnerExit.INPUT_OR_PREFLIGHT_INVALID, publishedArtifact = null)
        validated shouldContainExactly listOf("valid-distribution", "private-invalid-path")
        standardError shouldContainExactly
          listOf("performance-runner: INPUT_OR_PREFLIGHT_INVALID: DISTRIBUTION_INVALID")
        standardError.joinToString("\n").contains("private-invalid-path") shouldBe false
      }

      test("commands not yet implemented report unavailable at this checkpoint") {
        val standardError = mutableListOf<String>()
        val engine =
          RunnerEngine(
            RunnerDependencies(writeStandardError = { message -> standardError += message }),
          )
        val commands =
          listOf(
            RunnerCommand.Capture(emptyList()),
            RunnerCommand.Compare(emptyList()),
            RunnerCommand.Campaign(emptyList()),
            RunnerCommand.ScrubProfiler(emptyList()),
            RunnerCommand.FinalizeDiagnostic(emptyList()),
            RunnerCommand.FinalizeCampaign(emptyList()),
            RunnerCommand.Recover(emptyList()),
          )

        commands.map(engine::execute) shouldBe
          List(commands.size) {
            RunnerOutcome(
              exit = RunnerExit.INPUT_OR_PREFLIGHT_INVALID,
              publishedArtifact = null,
            )
          }
        standardError shouldContainExactly
          List(commands.size) {
            "performance-runner: INPUT_OR_PREFLIGHT_INVALID: COMMAND_NOT_AVAILABLE"
          }
      }
    },
  )
