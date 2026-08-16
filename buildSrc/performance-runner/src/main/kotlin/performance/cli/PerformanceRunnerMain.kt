/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.cli

import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess
import performance.runner.RunnerCommand
import performance.runner.RunnerDependencies
import performance.runner.RunnerEngine
import performance.runner.RunnerExit
import performance.runner.RunnerFailureReason

/** Starts the standalone performance runner. */
fun main(args: Array<String>) {
  exitProcess(runMain(args.toList(), RunnerDependencies.system()))
}

internal fun runMain(args: List<String>, dependencies: RunnerDependencies): Int =
  when (val parsedCommand = parseCommand(args)) {
    is CommandParseResult.Valid -> RunnerEngine(dependencies).execute(parsedCommand.command).exit.code
    is CommandParseResult.Invalid -> {
      dependencies.reportInputFailure(parsedCommand.reason)
      RunnerExit.INPUT_OR_PREFLIGHT_INVALID.code
    }
  }

private fun parseCommand(args: List<String>): CommandParseResult =
  when (args.firstOrNull()) {
    "validate-distribution" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags = setOf("--distribution"),
        create = RunnerCommand::ValidateDistribution,
      )

    "capture" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags =
          setOf(
            "--profile",
            "--forks",
            "--host-id",
            "--session-id",
            "--sequence",
            "--distribution",
            "--diagnostic-profiler",
            "--output",
          ),
        create = RunnerCommand::Capture,
      )

    "compare" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags =
          setOf(
            "--kind",
            "--runner-distribution",
            "--baseline",
            "--candidate",
            "--calibration",
            "--regression-policy",
            "--output",
          ),
        create = RunnerCommand::Compare,
      )

    "campaign" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags =
          setOf(
            "--profile",
            "--host-id",
            "--baseline-distribution",
            "--candidate-distribution",
            "--regression-policy",
            "--output",
          ),
        create = RunnerCommand::Campaign,
      )

    "scrub-profiler" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags = emptySet(),
        create = RunnerCommand::ScrubProfiler,
      )

    "finalize-diagnostic" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags = emptySet(),
        create = RunnerCommand::FinalizeDiagnostic,
      )

    "finalize-campaign" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags = emptySet(),
        create = RunnerCommand::FinalizeCampaign,
      )

    "recover" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags = emptySet(),
        create = RunnerCommand::Recover,
      )

    else -> CommandParseResult.Invalid(RunnerFailureReason.INVALID_COMMAND)
  }

private fun parseKnownCommand(
  arguments: List<String>,
  allowedFlags: Set<String>,
  create: (List<String>) -> RunnerCommand,
): CommandParseResult =
  when (validateArguments(arguments = arguments, allowedFlags = allowedFlags)) {
    null -> CommandParseResult.Valid(create(arguments))
    else -> CommandParseResult.Invalid(RunnerFailureReason.INVALID_ARGUMENTS)
  }

private tailrec fun validateArguments(
  arguments: List<String>,
  allowedFlags: Set<String>,
  seenFlags: Set<String> = emptySet(),
): RunnerFailureReason? =
  when {
    arguments.isEmpty() -> null
    arguments.size < 2 -> RunnerFailureReason.INVALID_ARGUMENTS
    arguments.first() !in allowedFlags -> RunnerFailureReason.INVALID_ARGUMENTS
    arguments.first() in seenFlags -> RunnerFailureReason.INVALID_ARGUMENTS
    arguments[1].startsWith("--") -> RunnerFailureReason.INVALID_ARGUMENTS
    arguments.first() == "--output" && isAbsoluteOrInvalid(arguments[1]) ->
      RunnerFailureReason.INVALID_ARGUMENTS

    else ->
      validateArguments(
        arguments = arguments.drop(2),
        allowedFlags = allowedFlags,
        seenFlags = seenFlags + arguments.first(),
      )
  }

private fun isAbsoluteOrInvalid(value: String): Boolean =
  try {
    Path.of(value).isAbsolute
  } catch (_: InvalidPathException) {
    true
  }

private sealed interface CommandParseResult {
  data class Valid(val command: RunnerCommand) : CommandParseResult

  data class Invalid(val reason: RunnerFailureReason) : CommandParseResult
}
