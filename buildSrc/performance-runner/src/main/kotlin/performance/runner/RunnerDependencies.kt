/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.runner

import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.time.Clock
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import performance.compare.CaptureComparator
import performance.compare.ComparisonComputation
import performance.compare.ComparisonKind
import performance.compare.ComparisonRequest
import performance.compare.RegressionPolicy
import performance.campaign.CampaignRunner
import performance.campaign.CampaignStatus
import performance.campaign.DefaultRolePreconditioner
import performance.campaign.ProvisionalCalibrationEvaluator
import performance.capture.CaptureOutcome
import performance.capture.CaptureRunner
import performance.distribution.DistributionValidation
import performance.distribution.DistributionValidationRequest
import performance.distribution.DistributionValidator
import performance.distribution.JavaRuntimeIdentity
import performance.capture.ProfilerScrubOutcome
import performance.capture.ProfilerScrubRequest
import performance.capture.ProfilerScrubber
import performance.finalize.CampaignFinalizationRequest
import performance.finalize.DiagnosticFinalizationRequest
import performance.finalize.EvidenceFinalizer
import performance.finalize.FinalizationOutcome
import performance.finalize.FreezeFinalizationRequest
import performance.finalize.ProfilerFinalizationEvidence
import performance.finalize.PrivateOperationFinalizer
import performance.finalize.StandaloneComparisonFinalizationRequest
import performance.hash.Sha256
import performance.publication.RecoveryOutcome
import performance.publication.ChecksumManifest
import performance.publication.StagingRecovery
import performance.process.JdkProcessExecutor

/** Side-effecting process boundaries supplied to the pure runner engine. */
internal class RunnerDependencies(
  private val writeStandardError: (String) -> Unit,
  private val validateDistribution: (String) -> Boolean = { false },
) {
  private var executeCommand: ((RunnerCommand) -> RunnerOutcome?)? = null

  internal fun reportInputFailure(reason: RunnerFailureReason) {
    writeStandardError("performance-runner: INPUT_OR_PREFLIGHT_INVALID: ${reason.name}")
  }

  internal fun distributionIsValid(path: String): Boolean = validateDistribution(path)

  internal fun execute(command: RunnerCommand): RunnerOutcome? = executeCommand?.invoke(command)

  internal companion object {
    @JvmSynthetic
    fun system(): RunnerDependencies =
      RunnerDependencies(
        writeStandardError = { message -> System.err.println(message) },
        validateDistribution = ::validateSystemDistribution,
      ).also { dependencies ->
        dependencies.executeCommand = { command ->
          executeSystemCommand(command, dependencies::reportInputFailure)
        }
      }

    @JvmSynthetic
    internal fun forTest(
      writeStandardError: (String) -> Unit,
      executeCommand: (RunnerCommand) -> RunnerOutcome?,
    ): RunnerDependencies =
      RunnerDependencies(
        writeStandardError = writeStandardError,
      ).also { dependencies -> dependencies.executeCommand = executeCommand }

    private fun executeSystemCommand(
      command: RunnerCommand,
      reportInputFailure: (RunnerFailureReason) -> Unit,
    ): RunnerOutcome? =
      runCatching {
          when (command) {
            is RunnerCommand.Capture ->
              capture(command.arguments, reportInputFailure)
            is RunnerCommand.Compare -> compare(command.arguments, reportInputFailure)
            is RunnerCommand.Campaign ->
              campaign(command.arguments, reportInputFailure)
            is RunnerCommand.FinalizeDiagnostic -> finalizeDiagnostic(command.arguments)
            is RunnerCommand.FinalizeStandaloneComparison ->
              finalizeStandaloneComparison(command.arguments)
            is RunnerCommand.FinalizeCampaign -> finalizeCampaign(command.arguments)
            is RunnerCommand.FinalizeFreeze -> finalizeFreeze(command.arguments)
            is RunnerCommand.ScrubProfiler -> scrubProfiler(command.arguments)
            is RunnerCommand.Recover -> recover(command.arguments)
            else -> null
          }
        }
        .getOrElse { RunnerOutcome(RunnerExit.INTERNAL_OR_PUBLICATION_FAILED, null) }

    private fun finalizeDiagnostic(arguments: List<String>): RunnerOutcome {
      val flags = arguments.toFlagMap()
      val terminal = terminal(flags.getValue("--terminal"))
      if (flags.containsKey("--operation-state")) {
        return PrivateOperationFinalizer
          .diagnostic(
            operationRoot = Path.of(flags.getValue("--source")),
            stateRoot = Path.of(flags.getValue("--operation-state")),
            qualificationRoot = Path.of(flags.getValue("--qualification-root")),
            artifactParent = Path.of(flags.getValue("--artifact-parent")),
            runToken = flags.getValue("--run-token"),
            terminal = terminal,
          ).runnerOutcome()
      }
      val profiler =
        flags["--operation-root"]?.let { operation ->
          ProfilerFinalizationEvidence(
            operationRoot = Path.of(operation),
            intentPath = Path.of(flags.getValue("--profiler-intent")),
            completionPath = Path.of(flags.getValue("--profiler-completion")),
            provisionalCaptureSha256 =
              Sha256.parse(flags.getValue("--provisional-capture-sha256")),
          )
        }
      val outcome =
        EvidenceFinalizer.system()
          .finalizeDiagnostic(
            DiagnosticFinalizationRequest(
              sourceRoot = Path.of(flags.getValue("--source")),
              artifactParent = Path.of(flags.getValue("--artifact-parent")),
              runToken = flags.getValue("--run-token"),
              terminal = terminal,
              profiler = profiler,
            ),
          )
      return outcome.runnerOutcome()
    }

    private fun finalizeCampaign(arguments: List<String>): RunnerOutcome {
      val flags = arguments.toFlagMap()
      if (flags.containsKey("--operation-state")) {
        return PrivateOperationFinalizer
          .campaign(
            stateRoot = Path.of(flags.getValue("--operation-state")),
            qualificationRoot = Path.of(flags.getValue("--qualification-root")),
            artifactParent = Path.of(flags.getValue("--artifact-parent")),
            runToken = flags.getValue("--run-token"),
            terminal = terminal(flags.getValue("--terminal")),
          ).runnerOutcome()
      }
      val outcome =
        EvidenceFinalizer.system()
          .finalizeCampaign(
            CampaignFinalizationRequest(
              sourceRoot = Path.of(flags.getValue("--source")),
              artifactParent = Path.of(flags.getValue("--artifact-parent")),
              runToken = flags.getValue("--run-token"),
              terminal = terminal(flags.getValue("--terminal")),
            ),
          )
      return outcome.runnerOutcome()
    }

    private fun finalizeStandaloneComparison(arguments: List<String>): RunnerOutcome {
      val flags = arguments.toFlagMap()
      val outcome =
        EvidenceFinalizer.system()
          .finalizeStandaloneComparison(
            StandaloneComparisonFinalizationRequest(
              sourceRoot = Path.of(flags.getValue("--source")),
              artifactParent = Path.of(flags.getValue("--artifact-parent")),
              runToken = flags.getValue("--run-token"),
              terminal = terminal(flags.getValue("--terminal")),
            ),
          )
      return outcome.runnerOutcome()
    }

    private fun capture(
      arguments: List<String>,
      reportInputFailure: (RunnerFailureReason) -> Unit,
    ): RunnerOutcome {
      val flags = arguments.toFlagMap()
      val distribution = validateSystemDistributionResult(flags.getValue("--distribution"))
      if (distribution == null) {
        reportInputFailure(RunnerFailureReason.DISTRIBUTION_INVALID)
        return RunnerOutcome(RunnerExit.INPUT_OR_PREFLIGHT_INVALID, null)
      }
      val request =
        runCatching { OperationRequestFactory.capture(flags, distribution) }
          .getOrElse {
            reportInputFailure(RunnerFailureReason.INVALID_ARGUMENTS)
            return RunnerOutcome(RunnerExit.INPUT_OR_PREFLIGHT_INVALID, null)
          }
      return when (val outcome = CaptureRunner(JdkProcessExecutor()).capture(request)) {
        is CaptureOutcome.Provisional -> {
          PrivateOperationWriter.capture(request, outcome)
          RunnerOutcome(RunnerExit.SUCCESS, null)
        }
        is CaptureOutcome.Invalid -> RunnerOutcome(outcome.exit, null)
      }
    }

    private fun compare(
      arguments: List<String>,
      reportInputFailure: (RunnerFailureReason) -> Unit,
    ): RunnerOutcome {
      val flags = arguments.toFlagMap()
      if (!validateSystemDistribution(flags.getValue("--runner-distribution"))) {
        reportInputFailure(RunnerFailureReason.DISTRIBUTION_INVALID)
        return RunnerOutcome(RunnerExit.INPUT_OR_PREFLIGHT_INVALID, null)
      }
      val policy =
        flags["--regression-policy"]?.let { path -> RegressionPolicy.parse(Files.readAllBytes(Path.of(path))) }
      val computation =
        CaptureComparator()
          .compare(
            ComparisonRequest(
              runnerDistribution = Path.of(flags.getValue("--runner-distribution")),
              kind = ComparisonKind.valueOf(flags.getValue("--kind").uppercase()),
              baseline = Path.of(flags.getValue("--baseline")),
              candidate = Path.of(flags.getValue("--candidate")),
              calibration = flags["--calibration"]?.let(Path::of),
              regressionPolicy = policy,
            ),
          )
      return when (computation) {
        is ComparisonComputation.Completed ->
          materializeComparison(flags.getValue("--output"), computation.jsonBytes, computation.markdownBytes, computation.exit)
        is ComparisonComputation.Incompatible ->
          materializeComparison(flags.getValue("--output"), computation.jsonBytes, computation.markdownBytes, computation.exit)
        is ComparisonComputation.InputFailure -> RunnerOutcome(computation.exit, null)
        is ComparisonComputation.InternalFailure -> RunnerOutcome(computation.exit, null)
      }
    }

    private fun materializeComparison(
      rawOutput: String,
      jsonBytes: ByteArray,
      markdownBytes: ByteArray,
      exit: RunnerExit,
    ): RunnerOutcome {
      val output = Path.of(rawOutput).toAbsolutePath().normalize()
      require(!Files.exists(output) && Files.isDirectory(output.parent) && !Files.isSymbolicLink(output.parent))
      Files.createDirectory(output)
      writeFsynced(output.resolve("comparison.json"), jsonBytes)
      writeFsynced(output.resolve("comparison.md"), markdownBytes)
      ChecksumManifest.write(output)
      return RunnerOutcome(exit, null)
    }

    private fun campaign(
      arguments: List<String>,
      reportInputFailure: (RunnerFailureReason) -> Unit,
    ): RunnerOutcome {
      val flags = arguments.toFlagMap()
      val baseline = validateSystemDistributionResult(flags.getValue("--baseline-distribution"))
      val candidate = validateSystemDistributionResult(flags.getValue("--candidate-distribution"))
      if (baseline == null || candidate == null) {
        reportInputFailure(RunnerFailureReason.DISTRIBUTION_INVALID)
        return RunnerOutcome(RunnerExit.INPUT_OR_PREFLIGHT_INVALID, null)
      }
      val request =
        runCatching { OperationRequestFactory.campaign(flags, baseline, candidate) }
          .getOrElse {
            reportInputFailure(RunnerFailureReason.INVALID_ARGUMENTS)
            return RunnerOutcome(RunnerExit.INPUT_OR_PREFLIGHT_INVALID, null)
          }
      val outcome =
        CampaignRunner(
            captureRunner = CaptureRunner(JdkProcessExecutor()),
            calibrationEvaluator = ProvisionalCalibrationEvaluator(),
            rolePreconditioner =
              DefaultRolePreconditioner(sleeper = { duration -> Thread.sleep(duration) }),
            clock = Clock.systemUTC(),
          )
          .run(request)
      return if (outcome.status == CampaignStatus.INVALID) {
        RunnerOutcome(RunnerExit.MEASUREMENT_INVALID, null)
      } else {
        PrivateOperationWriter.campaign(request, outcome, flags)
        RunnerOutcome(RunnerExit.SUCCESS, null)
      }
    }

    private fun writeFsynced(path: Path, bytes: ByteArray) {
      FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
      }
    }

    private fun finalizeFreeze(arguments: List<String>): RunnerOutcome {
      val flags = arguments.toFlagMap()
      val outcome =
        EvidenceFinalizer.system()
          .finalizeFreeze(
            FreezeFinalizationRequest(
              sourceRoot = Path.of(flags.getValue("--source")),
              artifactParent = Path.of(flags.getValue("--artifact-parent")),
              runToken = flags.getValue("--run-token"),
              terminal = terminal(flags.getValue("--terminal")),
            ),
            verifyDistribution = { root -> validateSystemDistribution(root.toString()) },
          )
      return outcome.runnerOutcome()
    }

    private fun scrubProfiler(arguments: List<String>): RunnerOutcome {
      val flags = arguments.toFlagMap()
      val outcome =
        ProfilerScrubber()
          .scrub(
            ProfilerScrubRequest(
              captureId = flags.getValue("--capture-id"),
              provisionalCaptureSha256 =
                Sha256.parse(flags.getValue("--provisional-capture-sha256")),
              expectedRawInputSha256 = Sha256.parse(flags.getValue("--raw-input-sha256")),
              variantSha256 = Sha256.parse(flags.getValue("--variant-sha256")),
              settingsSha256 = Sha256.parse(flags.getValue("--settings-sha256")),
              rawPath = Path.of(flags.getValue("--raw")),
              summaryPath = Path.of(flags.getValue("--summary")),
              intentPath = Path.of(flags.getValue("--intent")),
              completionPath = Path.of(flags.getValue("--completion")),
            ),
          )
      return RunnerOutcome(
        exit =
          when (outcome) {
            is ProfilerScrubOutcome.Completed -> RunnerExit.SUCCESS
            is ProfilerScrubOutcome.Invalid -> RunnerExit.MEASUREMENT_INVALID
          },
        publishedArtifact = null,
      )
    }

    private fun recover(arguments: List<String>): RunnerOutcome {
      val flags = arguments.toFlagMap()
      val authorizedToken = flags.getValue("--run-token")
      val authorizedInput = Path.of(flags.getValue("--operation-input"))
      val outcomes =
        StagingRecovery.system(
            authority = { token -> authorizedInput.takeIf { token == authorizedToken } },
            verifyDistribution = { root -> validateSystemDistribution(root.toString()) },
          )
          .recover(Path.of(flags.getValue("--artifact-root")))
      val failed = outcomes.any { it is RecoveryOutcome.Failed }
      return RunnerOutcome(
        if (failed) RunnerExit.INTERNAL_OR_PUBLICATION_FAILED else RunnerExit.SUCCESS,
        outcomes.filterIsInstance<RecoveryOutcome.Published>().singleOrNull()?.target,
      )
    }

    private fun List<String>.toFlagMap(): Map<String, String> =
      chunked(2).associate { pair -> pair[0] to pair[1] }

    private fun terminal(raw: String): RunnerExit =
      RunnerExit.entries.single { it.code == raw.toInt() }

    private fun FinalizationOutcome.runnerOutcome(): RunnerOutcome =
      when (this) {
        is FinalizationOutcome.Published -> RunnerOutcome(exit, root)
        is FinalizationOutcome.Rejected -> RunnerOutcome(exit, null)
      }

    private fun validateSystemDistribution(rawPath: String): Boolean =
      validateSystemDistributionResult(rawPath) != null

    private fun validateSystemDistributionResult(rawPath: String): performance.distribution.VerifiedDistribution? =
      runCatching {
          val executable =
            Path.of(
                checkNotNull(ProcessHandle.current().info().command().orElse(null)) {
                  "current Java executable is unavailable"
                },
              )
              .toAbsolutePath()
              .normalize()
          val result =
            DistributionValidator()
              .validate(
                DistributionValidationRequest(
                  root = Path.of(rawPath),
                  selectedJava =
                    JavaRuntimeIdentity(
                      executable = executable,
                      featureVersion = Runtime.version().feature(),
                      sha256 = Sha256.digest(Files.readAllBytes(executable)),
                    ),
                ),
              )
          (result as? DistributionValidation.Valid)?.distribution
        }
        .getOrNull()
  }
}

internal enum class RunnerFailureReason {
  INVALID_COMMAND,
  INVALID_ARGUMENTS,
  DISTRIBUTION_INVALID,
  COMMAND_NOT_AVAILABLE,
}
