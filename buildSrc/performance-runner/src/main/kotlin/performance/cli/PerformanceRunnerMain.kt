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

/** Runs the frozen performance-runner CLI and exits with its stable command-specific status. */
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
        requiredFlags =
          setOf(
            "--profile",
            "--forks",
            "--host-id",
            "--session-id",
            "--sequence",
            "--distribution",
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
        requiredFlags =
          setOf(
            "--kind",
            "--runner-distribution",
            "--baseline",
            "--candidate",
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
        requiredFlags =
          setOf(
            "--profile",
            "--host-id",
            "--baseline-distribution",
            "--candidate-distribution",
            "--output",
          ),
        create = RunnerCommand::Campaign,
      )

    "scrub-profiler" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags =
          setOf(
            "--capture-id",
            "--provisional-capture-sha256",
            "--operation-state",
            "--qualification-root",
            "--raw-input-sha256",
            "--variant-sha256",
            "--settings-sha256",
            "--raw",
            "--summary",
            "--intent",
            "--completion",
          ),
        requiredFlags =
          setOf(
            "--capture-id",
            "--provisional-capture-sha256",
            "--raw-input-sha256",
            "--variant-sha256",
            "--settings-sha256",
            "--raw",
            "--summary",
            "--intent",
            "--completion",
          ),
        create = RunnerCommand::ScrubProfiler,
      )

    "finalize-diagnostic" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags =
          setOf(
            "--source",
            "--artifact-parent",
            "--run-token",
            "--terminal",
            "--operation-state",
            "--qualification-root",
            "--operation-root",
            "--profiler-intent",
            "--profiler-completion",
            "--provisional-capture-sha256",
          ),
        requiredFlags = setOf("--source", "--artifact-parent", "--run-token", "--terminal"),
        allOrNoneFlags =
          setOf(
            "--operation-root",
            "--profiler-intent",
            "--profiler-completion",
            "--provisional-capture-sha256",
          ),
        secondaryAllOrNoneFlags = setOf("--operation-state", "--qualification-root"),
        create = RunnerCommand::FinalizeDiagnostic,
      )

    "finalize-standalone-comparison" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags =
          setOf(
            "--source",
            "--artifact-parent",
            "--run-token",
            "--terminal",
            "--operation-state",
            "--qualification-root",
          ),
        requiredFlags = setOf("--source", "--artifact-parent", "--run-token", "--terminal"),
        allOrNoneFlags = setOf("--operation-state", "--qualification-root"),
        create = RunnerCommand::FinalizeStandaloneComparison,
      )

    "finalize-campaign" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags =
          setOf(
            "--source",
            "--artifact-parent",
            "--run-token",
            "--terminal",
            "--operation-state",
            "--qualification-root",
          ),
        requiredFlags = setOf("--source", "--artifact-parent", "--run-token", "--terminal"),
        allOrNoneFlags = setOf("--operation-state", "--qualification-root"),
        create = RunnerCommand::FinalizeCampaign,
      )

    "finalize-freeze" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags = setOf("--source", "--artifact-parent", "--run-token", "--terminal"),
        requiredFlags = setOf("--source", "--artifact-parent", "--run-token", "--terminal"),
        create = RunnerCommand::FinalizeFreeze,
      )

    "recover" ->
      parseKnownCommand(
        arguments = args.drop(1),
        allowedFlags = setOf("--artifact-root", "--run-token", "--operation-input"),
        requiredFlags = setOf("--artifact-root", "--run-token", "--operation-input"),
        create = RunnerCommand::Recover,
      )

    else -> CommandParseResult.Invalid(RunnerFailureReason.INVALID_COMMAND)
  }

private fun parseKnownCommand(
  arguments: List<String>,
  allowedFlags: Set<String>,
  requiredFlags: Set<String> = emptySet(),
  allOrNoneFlags: Set<String> = emptySet(),
  secondaryAllOrNoneFlags: Set<String> = emptySet(),
  create: (List<String>) -> RunnerCommand,
): CommandParseResult {
  val providedFlags = arguments.filterIndexed { index, _ -> index % 2 == 0 }.toSet()
  val groupedFlagsComplete =
    allOrNoneFlags.none(providedFlags::contains) || providedFlags.containsAll(allOrNoneFlags)
  val secondaryGroupedFlagsComplete =
    secondaryAllOrNoneFlags.none(providedFlags::contains) ||
      providedFlags.containsAll(secondaryAllOrNoneFlags)
  val valid =
    validateArguments(arguments = arguments, allowedFlags = allowedFlags) == null &&
      providedFlags.containsAll(requiredFlags) && groupedFlagsComplete && secondaryGroupedFlagsComplete
  return if (valid) CommandParseResult.Valid(create(arguments))
  else CommandParseResult.Invalid(RunnerFailureReason.INVALID_ARGUMENTS)
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
