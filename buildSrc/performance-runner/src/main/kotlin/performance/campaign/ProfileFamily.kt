/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.campaign

import java.time.Duration
import performance.capture.CaptureProfile
import performance.capture.CaptureProfileFamily
import performance.capture.DiagnosticProfiler

/** Complete immutable no-profiler ladder for one baseline/candidate profile family. */
internal class ProfileFamily private constructor(
  val family: CaptureProfileFamily,
  private val baselineProfiles: Map<Int, CaptureProfile>,
  private val candidateProfiles: Map<Int, CaptureProfile>,
  val settleDuration: Duration,
  val maximumSessionDuration: Duration,
) {
  val id: String = family.id
  val forkLadder: List<Int> = FORK_LADDER

  internal fun profile(role: CaptureRole, forks: Int): CaptureProfile =
    when (role) {
      CaptureRole.BASELINE_A1,
      CaptureRole.BASELINE_A2 -> baselineProfiles
      CaptureRole.CANDIDATE_B -> candidateProfiles
    }.getValue(forks)

  companion object {
    fun create(
      family: CaptureProfileFamily,
      baselineProfiles: Map<Int, CaptureProfile>,
      candidateProfiles: Map<Int, CaptureProfile>,
      settleDuration: Duration = Duration.ofSeconds(10),
      maximumSessionDuration: Duration = Duration.ofHours(2),
    ): ProfileFamily {
      require(family != CaptureProfileFamily.CANARY)
      require(settleDuration == Duration.ofSeconds(10))
      require(maximumSessionDuration == Duration.ofHours(2))
      require(baselineProfiles.keys == FORK_LADDER.toSet())
      require(candidateProfiles.keys == FORK_LADDER.toSet())
      val orderedProfiles =
        FORK_LADDER.flatMap { forks ->
          listOf(baselineProfiles.getValue(forks), candidateProfiles.getValue(forks))
        }
      require(
        orderedProfiles.all { profile ->
          profile.family == family &&
            profile.forks in FORK_LADDER &&
            profile.profiler == DiagnosticProfiler.NONE
        },
      )
      require(orderedProfiles.map(CaptureProfile::expectedProtocolSha256).distinct().size == 1)
      FORK_LADDER.forEach { forks ->
        require(
          baselineProfiles.getValue(forks).forks == forks &&
            candidateProfiles.getValue(forks).forks == forks,
        )
        require(
          comparableVariant(baselineProfiles.getValue(forks)) ==
            comparableVariant(candidateProfiles.getValue(forks)),
        )
      }
      return ProfileFamily(
        family = family,
        baselineProfiles = baselineProfiles.toMap(),
        candidateProfiles = candidateProfiles.toMap(),
        settleDuration = settleDuration,
        maximumSessionDuration = maximumSessionDuration,
      )
    }

    private fun comparableVariant(profile: CaptureProfile): List<Any> =
      listOf(
        profile.family,
        profile.identity,
        profile.variantSha256,
        profile.forks,
        profile.warmupIterations,
        profile.measurementIterations,
        profile.batchSize,
        profile.threads,
        profile.mode,
        profile.unit,
        profile.profiler,
        profile.profilerArguments,
        profile.jvmArguments,
        profile.expectedCells,
        profile.expectedProtocolSha256,
        profile.selectedJavaExecutable,
        profile.selectedJavaSha256,
      )

    private val FORK_LADDER = listOf(10, 20, 40)
  }
}
