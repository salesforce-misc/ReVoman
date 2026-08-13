/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Cs2aSupervisorAtomicHandoffTest {
  @TempDir lateinit var temporaryDirectory: Path

  @Test
  fun `failed handoff never exposes a partially copied final directory`() {
    val fixture = createFixture("interrupted")

    val result = runCopy(fixture, installFailureAfter = 3)

    assertThat(result.exitCode).isNotEqualTo(0)
    assertThat(Files.exists(fixture.destination, LinkOption.NOFOLLOW_LINKS)).isFalse()
    assertThat(hiddenStages(fixture)).isEmpty()
  }

  @Test
  fun `handoff publishes an exact immutable tree and accepts an identical retry`() {
    val fixture = createFixture("complete")
    assertProcessSucceeds(runCopy(fixture))
    val publishedFile = fixture.destination.resolve("finished-at.txt")
    val sentinelTime = FileTime.fromMillis(1_000)
    Files.setLastModifiedTime(publishedFile, sentinelTime)

    assertProcessSucceeds(runCopy(fixture))

    assertThat(Files.getLastModifiedTime(publishedFile)).isEqualTo(sentinelTime)
    assertThat(directoryNames(fixture.destination)).containsExactlyElementsIn(CORE_STATE_FILES)
    assertThat(posixMode(fixture.destination)).containsExactlyElementsIn(MODE_0700)
    CORE_STATE_FILES.forEach { name ->
      val source = fixture.state.resolve(name)
      val destination = fixture.destination.resolve(name)
      assertThat(Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)).isTrue()
      assertThat(Files.readString(destination)).isEqualTo(Files.readString(source))
      assertThat(posixMode(destination)).containsExactlyElementsIn(MODE_0400)
    }
    assertThat(hiddenStages(fixture)).isEmpty()
  }

  @Test
  fun `retry rejects stale partial nonregular and symlink destinations without replacement`() {
    val stale = createFixture("stale")
    assertProcessSucceeds(runCopy(stale))
    val publishedFinishedAt = Files.readString(stale.destination.resolve("finished-at.txt"))
    writeRootStateFile(stale.state.resolve("finished-at.txt"), "new-finished-at\n")

    assertThat(runCopy(stale).exitCode).isNotEqualTo(0)
    assertThat(Files.readString(stale.destination.resolve("finished-at.txt")))
      .isEqualTo(publishedFinishedAt)

    val partial = createFixture("partial")
    Files.createDirectory(partial.destination)
    Files.setPosixFilePermissions(partial.destination, MODE_0700)
    Files.copy(
      partial.state.resolve(CORE_STATE_FILES.first()),
      partial.destination.resolve(CORE_STATE_FILES.first()),
    )
    Files.setPosixFilePermissions(
      partial.destination.resolve(CORE_STATE_FILES.first()),
      MODE_0400,
    )
    assertThat(runCopy(partial).exitCode).isNotEqualTo(0)
    assertThat(directoryNames(partial.destination)).containsExactly(CORE_STATE_FILES.first())

    val nonregular = createFixture("nonregular")
    Files.createDirectory(nonregular.destination)
    Files.setPosixFilePermissions(nonregular.destination, MODE_0700)
    Files.createDirectory(nonregular.destination.resolve(CORE_STATE_FILES.first()))
    assertThat(runCopy(nonregular).exitCode).isNotEqualTo(0)
    assertThat(Files.isDirectory(nonregular.destination.resolve(CORE_STATE_FILES.first()))).isTrue()

    val symlink = createFixture("symlink")
    val external = Files.createDirectory(temporaryDirectory.resolve("external-final-state"))
    Files.createSymbolicLink(symlink.destination, external)
    assertThat(runCopy(symlink).exitCode).isNotEqualTo(0)
    assertThat(Files.isSymbolicLink(symlink.destination)).isTrue()
  }

  @Test
  fun `handoff rejects a source that is not modeled as root owned mode 0400`() {
    val wrongOwner = createFixture("wrong-owner")
    assertThat(runCopy(wrongOwner, modelSourceAsRoot = false).exitCode).isNotEqualTo(0)
    assertThat(Files.exists(wrongOwner.destination, LinkOption.NOFOLLOW_LINKS)).isFalse()

    val wrongMode = createFixture("wrong-mode")
    Files.setPosixFilePermissions(wrongMode.state.resolve(CORE_STATE_FILES.first()), MODE_0600)
    assertThat(runCopy(wrongMode).exitCode).isNotEqualTo(0)
    assertThat(Files.exists(wrongMode.destination, LinkOption.NOFOLLOW_LINKS)).isFalse()

    val symlinkSource = createFixture("symlink-source")
    val source = symlinkSource.state.resolve(CORE_STATE_FILES.first())
    Files.delete(source)
    Files.createSymbolicLink(source, symlinkSource.state.resolve(CORE_STATE_FILES.last()))
    assertThat(runCopy(symlinkSource).exitCode).isNotEqualTo(0)
    assertThat(Files.exists(symlinkSource.destination, LinkOption.NOFOLLOW_LINKS)).isFalse()
  }

  @Test
  fun `finalization executes the production handoff call site and its deletion mutant fails`() {
    val source = Files.readString(supervisor)
    val invocation = "if ! copy_final_state_to_run_root \"${'$'}AUTHENTICATED_RUN_ROOT\"; then"
    val mutant = source.replace(invocation, "if ! :; then")
    assertThat(mutant).isNotEqualTo(source)
    val originalScript = temporaryDirectory.resolve("call-site-original.sh")
    val mutantScript = temporaryDirectory.resolve("call-site-mutant.sh")
    Files.writeString(originalScript, source)
    Files.writeString(mutantScript, mutant)

    assertProcessSucceeds(runFinalizeHarness(originalScript, "original"))
    assertThat(runFinalizeHarness(mutantScript, "mutant").exitCode).isNotEqualTo(0)
  }

  @Test
  fun `publish final handoff mode creates the exact post-run tree and rejects stale retry`() {
    val fixture = createFixture("publish-final")
    writeRootStateFile(fixture.state.resolve("operator-post-supervisor-exit.txt"), "0\n")
    assertProcessSucceeds(runCopy(fixture))

    assertProcessSucceeds(runPublishFinal(fixture))

    assertThat(directoryNames(fixture.finalDestination))
      .containsExactlyElementsIn(FINAL_STATE_FILES)
    FINAL_STATE_FILES.forEach { name ->
      val source = fixture.state.resolve(name)
      val destination = fixture.finalDestination.resolve(name)
      assertThat(Files.readString(destination)).isEqualTo(Files.readString(source))
      assertThat(posixMode(destination)).containsExactlyElementsIn(MODE_0400)
    }
    assertThat(posixMode(fixture.finalDestination)).containsExactlyElementsIn(MODE_0700)

    val publishedPostStatus =
      Files.readString(fixture.finalDestination.resolve("operator-post-supervisor-exit.txt"))
    writeRootStateFile(fixture.state.resolve("operator-post-supervisor-exit.txt"), "70\n")
    assertThat(runPublishFinal(fixture).exitCode).isNotEqualTo(0)
    assertThat(
        Files.readString(fixture.finalDestination.resolve("operator-post-supervisor-exit.txt"))
      )
      .isEqualTo(publishedPostStatus)
  }

  @Test
  fun `publish final handoff mode validates exact state path and executable dispatch`() {
    val fixture = createFixture("publish-dispatch")
    writeRootStateFile(fixture.state.resolve("operator-post-supervisor-exit.txt"), "0\n")
    assertProcessSucceeds(runCopy(fixture))

    val invalidState = temporaryDirectory.resolve("governor-state.Escape123")
    assertThat(runPublishFinal(fixture, stateOverride = invalidState).exitCode).isNotEqualTo(0)
    assertThat(Files.exists(fixture.finalDestination, LinkOption.NOFOLLOW_LINKS)).isFalse()

    val source = Files.readString(fixture.runnableSupervisor)
    val invocation = "publish_final_handoff_main \"${'$'}2\" \"${'$'}3\""
    val mutant = source.replace(invocation, ":")
    assertThat(mutant).isNotEqualTo(source)
    val originalScript = temporaryDirectory.resolve("dispatch-original.sh")
    val mutantScript = temporaryDirectory.resolve("dispatch-mutant.sh")
    Files.writeString(originalScript, source)
    Files.writeString(mutantScript, mutant)

    assertProcessSucceeds(runDispatchHarness(originalScript, fixture, "original"))
    assertThat(runDispatchHarness(mutantScript, fixture, "mutant").exitCode).isNotEqualTo(0)
  }

  @Test
  fun `publish final handoff mode rejects malformed post status`() {
    val fixture = createFixture("malformed-post-status")
    writeRootStateFile(fixture.state.resolve("operator-post-supervisor-exit.txt"), "not-a-status\n")
    assertProcessSucceeds(runCopy(fixture))

    assertThat(runPublishFinal(fixture).exitCode).isNotEqualTo(0)
    assertThat(Files.exists(fixture.finalDestination, LinkOption.NOFOLLOW_LINKS)).isFalse()
  }

  @Test
  fun `publish final handoff proves the exact released lock and rejects call site mutations`() {
    val complete = createFixture("released-lock")
    writeRootStateFile(complete.state.resolve("operator-post-supervisor-exit.txt"), "0\n")
    assertProcessSucceeds(runCopy(complete))

    val completeResult = runPublishFinal(complete)

    assertProcessSucceeds(completeResult)
    assertThat(Files.readString(complete.state.resolve("lock-released.txt"))).isEqualTo("true\n")
    assertThat(posixMode(complete.state.resolve("lock-released.txt")))
      .containsExactlyElementsIn(MODE_0400)

    val contended = createFixture("contended-lock")
    writeRootStateFile(contended.state.resolve("operator-post-supervisor-exit.txt"), "0\n")
    assertProcessSucceeds(runCopy(contended))
    assertThat(runPublishFinal(contended, lockAvailable = false).exitCode).isNotEqualTo(0)
    assertThat(Files.exists(contended.state.resolve("lock-released.txt"))).isFalse()

    val substituted = createFixture("substituted-lock-fd")
    writeRootStateFile(substituted.state.resolve("operator-post-supervisor-exit.txt"), "0\n")
    assertProcessSucceeds(runCopy(substituted))
    assertThat(runPublishFinal(substituted, substituteLockFd = true).exitCode).isNotEqualTo(0)
    assertThat(Files.exists(substituted.state.resolve("lock-released.txt"))).isFalse()

    val mutationFixture = createFixture("deleted-lock-call-site")
    writeRootStateFile(
      mutationFixture.state.resolve("operator-post-supervisor-exit.txt"),
      "0\n",
    )
    assertProcessSucceeds(runCopy(mutationFixture))
    val source = Files.readString(mutationFixture.runnableSupervisor)
    val invocation =
      "authenticate_released_lock || fail \"benchmark lock release is not authenticated\""
    val mutant = source.replace(invocation, ": # deleted authenticate_released_lock")
    assertThat(mutant).isNotEqualTo(source)
    val mutantScript = temporaryDirectory.resolve("lock-call-site-mutant.sh")
    Files.writeString(mutantScript, mutant)
    assertThat(runPublishFinal(mutationFixture.copy(runnableSupervisor = mutantScript)).exitCode)
      .isNotEqualTo(0)
  }

  @Test
  fun `validate final handoff authenticates exact immutable evidence without writing or locking`() {
    val fixture = createFixture("validate-final")
    completeFinalHandoff(fixture)
    val before = treeSnapshot(fixture.runRoot.parent.parent)

    val result = runValidateFinal(fixture)

    assertProcessSucceeds(result)
    assertThat(treeSnapshot(fixture.runRoot.parent.parent)).isEqualTo(before)
  }

  @Test
  fun `validate final handoff rejects every evidence and crosslink mutation`() {
    val mutations =
      linkedMapOf<String, (HandoffFixture) -> Unit>(
        "extra final file" to
          { fixture ->
            writeRootStateFile(fixture.finalDestination.resolve("extra.txt"), "x\n")
          },
        "final file bytes" to
          { fixture ->
            writeRootStateFile(fixture.finalDestination.resolve("finished-at.txt"), "changed\n")
          },
        "run-root crosslink" to
          { fixture ->
            mutateFinalEvidence(
              fixture,
              "run-root.txt",
              "/opt/revoman-benchmark/runs/cs2a.Other123\n",
            )
          },
        "implementation crosslink" to
          { fixture ->
            mutateFinalEvidence(
              fixture,
              "implementation-sha.txt",
              "${"d".repeat(40)}\n",
            )
          },
        "executed supervisor crosslink" to
          { fixture ->
            val runnerSha = sha256(fixture.runnerFile)
            mutateFinalEvidence(
              fixture,
              "executed-script-sha256sums.tsv",
              "runner\t$runnerSha\nsupervisor\t${"0".repeat(64)}\n",
            )
          },
        "authenticated handoff crosslink" to
          { fixture ->
            val supervisorSha = sha256(fixture.runnableSupervisor)
            mutateFinalEvidence(
              fixture,
              "authenticated-handoff.tsv",
              "implementation\t$IMPLEMENTATION_SHA\nuid\t999999\n" +
                "runner\t${sha256(fixture.runnerFile)}\nsupervisor\t$supervisorSha\n",
            )
          },
        "lock release" to
          { fixture ->
            mutateFinalEvidence(fixture, "lock-released.txt", "false\n")
          },
        "lock provenance" to
          { fixture ->
            mutateFinalEvidence(
              fixture,
              "lock-provenance.txt",
              "0:0:600:16777232:999999\n",
            )
          },
        "restoration flag" to
          { fixture ->
            mutateFinalEvidence(fixture, "restoration-failed.txt", "true\n")
          },
        "containment flag" to
          { fixture ->
            mutateFinalEvidence(fixture, "containment-failed.txt", "true\n")
          },
        "governor equality" to
          { fixture ->
            mutateFinalEvidence(
              fixture,
              "restored-governors.tsv",
              "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor\tperformance\n",
            )
          },
        "final status equality" to
          { fixture ->
            mutateFinalEvidence(
              fixture,
              "operator-post-supervisor-exit.txt",
              "70\n",
            )
          },
      )

    mutations.forEach { (name, mutate) ->
      val fixture = createFixture("validate-mutation-${name.replace(' ', '-')}")
      completeFinalHandoff(fixture)
      mutate(fixture)
      val result = runValidateFinal(fixture)
      assertWithMessage("$name\n${result.output}").that(result.exitCode).isNotEqualTo(0)
    }
  }

  @Test
  fun `interrupted final publication exposes no final directory`() {
    val fixture = createFixture("interrupted-final")
    writeRootStateFile(fixture.state.resolve("operator-post-supervisor-exit.txt"), "0\n")
    assertProcessSucceeds(runCopy(fixture))

    val result = runPublishFinal(fixture, installFailureAfter = 4)

    assertThat(result.exitCode).isNotEqualTo(0)
    assertThat(Files.exists(fixture.finalDestination, LinkOption.NOFOLLOW_LINKS)).isFalse()
    assertThat(hiddenStages(fixture)).isEmpty()
  }

  @Test
  fun `core and final publications reject wrong destination directory and file metadata`() {
    PublicationKind.entries.forEach { kind ->
      val wrongDirectoryMode = createFixture("${kind.name.lowercase()}-directory-mode")
      preparePublication(wrongDirectoryMode, kind)
      Files.createDirectory(publicationDestination(wrongDirectoryMode, kind))
      Files.setPosixFilePermissions(publicationDestination(wrongDirectoryMode, kind), MODE_0755)
      assertPublicationRejected(wrongDirectoryMode, kind, "wrong directory mode")

      val wrongFileMode = createFixture("${kind.name.lowercase()}-file-mode")
      preparePublication(wrongFileMode, kind)
      assertProcessSucceeds(publish(wrongFileMode, kind))
      Files.setPosixFilePermissions(
        publicationDestination(wrongFileMode, kind).resolve(publicationFiles(kind).first()),
        MODE_0600,
      )
      assertPublicationRejected(wrongFileMode, kind, "wrong file mode")

      val wrongOwner = createFixture("${kind.name.lowercase()}-owner")
      preparePublication(wrongOwner, kind)
      assertProcessSucceeds(publish(wrongOwner, kind))
      val directoryOwnerResult = publish(wrongOwner, kind, destinationOwnerMutation = "directory")
      assertWithMessage("${kind.name} wrong directory owner\n${directoryOwnerResult.output}")
        .that(directoryOwnerResult.exitCode)
        .isNotEqualTo(0)
      val fileOwnerResult = publish(wrongOwner, kind, destinationOwnerMutation = "file")
      assertWithMessage("${kind.name} wrong file owner\n${fileOwnerResult.output}")
        .that(fileOwnerResult.exitCode)
        .isNotEqualTo(0)
    }
  }

  @Test
  fun `child process group termination propagates signal errors and proves bounded absence`() {
    val termFailure = runTerminationHarness(TerminationScenario.TERM_FAILURE)
    assertThat(termFailure.exitCode).isNotEqualTo(0)

    val killFailure = runTerminationHarness(TerminationScenario.KILL_FAILURE)
    assertThat(killFailure.exitCode).isNotEqualTo(0)

    val lingering = runTerminationHarness(TerminationScenario.LINGERING)
    assertThat(lingering.exitCode).isNotEqualTo(0)

    assertProcessSucceeds(runTerminationHarness(TerminationScenario.DISAPPEARS_AFTER_KILL))
  }

  @Test
  fun `signal before child pgid prevents launch and completes through finalization`() {
    val state = Files.createDirectory(temporaryDirectory.resolve("pre-launch-signal-state"))
    val launchMarker = temporaryDirectory.resolve("pre-launch-signal-launched")
    val result = runPreLaunchSignalHarness(state, launchMarker)

    assertThat(result.exitCode).isEqualTo(143)
    assertThat(Files.exists(launchMarker)).isFalse()
    assertThat(Files.readString(state.resolve("child-or-supervisor-status.txt"))).isEqualTo("143\n")
    assertThat(Files.readString(state.resolve("containment-failed.txt"))).isEqualTo("false\n")
  }

  @Test
  fun `signal before setsid establishes pgid kills and reaps child before benchmark progress`() {
    val progressMarker = temporaryDirectory.resolve("launch-race-progressed")

    val result = runLaunchAssignmentRaceHarness(progressMarker)

    assertWithMessage("process output:\n%s", result.output).that(result.exitCode).isEqualTo(0)
    assertThat(Files.exists(progressMarker)).isFalse()
  }

  @Test
  fun `ready marker is rejected when launcher ownership disappears before acceptance`() {
    val progressMarker = temporaryDirectory.resolve("ready-owner-progressed")

    val result = runReadyOwnershipLossHarness(progressMarker)

    assertWithMessage("process output:\n%s", result.output).that(result.exitCode).isEqualTo(0)
    assertThat(Files.exists(progressMarker)).isFalse()
  }

  @Test
  fun `signal at final release rename aborts launch even when containment fails`() {
    val progressMarker = temporaryDirectory.resolve("ready-edge-progressed")

    val result = runReadyReleaseSignalRaceHarness(progressMarker)

    assertWithMessage("process output:\n%s", result.output).that(result.exitCode).isEqualTo(0)
    assertThat(Files.exists(progressMarker)).isFalse()
  }

  @Test
  fun `nested signal during release edge cannot clear launch cancellation`() {
    val progressMarker = temporaryDirectory.resolve("nested-ready-edge-progressed")

    val result = runNestedReleaseCancellationHarness(progressMarker)

    assertWithMessage("process output:\n%s", result.output).that(result.exitCode).isEqualTo(0)
    assertThat(result.output).contains("received TERM")
    assertThat(result.output).contains("nested=true")
    assertThat(Files.exists(progressMarker)).isFalse()
  }

  @Test
  fun `nested signal during termination setup cannot latch containment guard`() {
    val result = runNestedTerminationGuardHarness()

    assertWithMessage("process output:\n%s", result.output).that(result.exitCode).isEqualTo(0)
    assertThat(result.output).contains("received TERM")
    assertThat(result.output).contains("nested=true")
  }

  @Test
  fun `release publication propagates write chmod and rename failures`() {
    listOf("write", "chmod", "rename").forEach { scenario ->
      val canonical = temporaryDirectory.resolve("release-failure-$scenario")
      val candidate = temporaryDirectory.resolve(".release-failure-$scenario.candidate")
      val result = runReleasePublicationFailureHarness(scenario, candidate, canonical)

      assertWithMessage("$scenario output:\n%s", result.output).that(result.exitCode).isEqualTo(0)
      assertThat(Files.exists(canonical)).isFalse()
    }
  }

  @Test
  fun `signal after child reap cannot target a recycled pid or process group`() {
    val result = runPostReapSignalHarness()

    assertWithMessage("process output:\n%s", result.output).that(result.exitCode).isEqualTo(0)
  }

  @Test
  fun `reaping group leader contains surviving child group before clearing pgid`() {
    val result = runPostLeaderReapDescendantHarness()

    assertWithMessage("process output:\n%s", result.output).that(result.exitCode).isEqualTo(0)
  }

  @Test
  fun `cleanup retains signal traps through containment and governor restoration`() {
    val state = Files.createDirectory(temporaryDirectory.resolve("cleanup-signal-state"))
    writeRootStateFile(
      state.resolve("original-governors.tsv"),
      "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor\tpowersave\n",
    )

    val result = runCleanupSignalHarness(state)

    assertThat(result.exitCode).isEqualTo(143)
    assertThat(Files.readString(state.resolve("child-or-supervisor-status.txt"))).isEqualTo("143\n")
    assertThat(Files.readString(state.resolve("restoration-failed.txt"))).isEqualTo("false\n")
    assertThat(Files.exists(state.resolve("finished-at.txt"))).isTrue()
  }

  @Test
  fun `controlled child launch scrubs environment closes extra descriptors and retains exact lock`() {
    val source = Files.readString(supervisor)
    val closeCall =
      "close_unapproved_child_descriptors || fail \"cannot close unapproved child descriptors\""
    val cleanExec = "exec /usr/bin/env -i"
    val lockProof =
      "verify_child_lock_descriptor || fail \"inherited benchmark lock descriptor substitution\""
    assertThat(source).contains(closeCall)
    assertThat(source).contains(cleanExec)
    assertThat(source).contains(lockProof)

    assertProcessSucceeds(runControlledChildHarness(source, "original"))
    assertThat(runControlledChildHarness(source.replace(closeCall, ":"), "no-close").exitCode)
      .isNotEqualTo(0)
    assertThat(
        runControlledChildHarness(source.replace(cleanExec, "exec /usr/bin/env"), "no-clean")
          .exitCode
      )
      .isNotEqualTo(0)
    assertThat(runControlledChildHarness(source, "substituted", substituteLock = true).exitCode)
      .isNotEqualTo(0)
    assertProcessSucceeds(
      runControlledChildHarness(
        source.replace(lockProof, ": # deleted child lock proof"),
        "no-lock-proof",
        substituteLock = true,
      )
    )
  }

  @Test
  fun `runuser reentry scrubs hostile bootstrap environment before starting bash`() {
    val source = Files.readString(supervisor)
    val scrubbedReentry =
      """/usr/sbin/runuser -u "${'$'}CONTROLLED_USER" -- \
    /usr/bin/env -i \
      PATH="${'$'}TRUSTED_CHILD_PATH" \
      CS2A_LOCK_FD=9 \
      CS2A_AUTHENTICATED_UID="${'$'}controlled_uid" \
      CS2A_IMPLEMENTATION_SHA="${'$'}implementation" \
      CS2A_AUTHENTICATED_RUNNER_SHA="${'$'}runner_sha" \
      /bin/bash "${'$'}0" --run-controlled-child"""
    val mutant = source.replace(scrubbedReentry, scrubbedReentry.replace("env -i", "env"))
    assertThat(mutant).isNotEqualTo(source)

    assertProcessSucceeds(runPreReentryHarness(source, "original"))
    assertThat(runPreReentryHarness(mutant, "no-reentry-scrub").exitCode).isNotEqualTo(0)
  }

  @Test
  fun `orphaned controlled launcher releases inherited benchmark lock`() {
    OrphanPhase.entries.forEach { phase ->
      val fixture = startOrphanedLauncherHarness(phase.name.lowercase(), phase)

      try {
        orphanParentAfterPhaseStarts(fixture)
        assertWithMessage(
            "$phase orphaned launcher group retained inherited FD9\n" +
              "parent:\n${Files.readString(fixture.parentLog)}\n" +
              "launcher:\n${Files.readString(fixture.launcherLog)}"
          )
          .that(eventuallyAcquiresLock(fixture.lockFile))
          .isTrue()
        val launcherLog = Files.readString(fixture.launcherLog)
        assertThat(launcherLog)
          .contains("authenticated supervisor parent disappeared; terminating controlled group")
        assertThat(launcherLog).contains("orphan watchdog sent TERM to controlled group")
        assertThat(launcherLog).contains("orphan watchdog escalating controlled group to KILL")
      } finally {
        stopOrphanedLauncherHarness(fixture)
      }
    }

    val mutant =
      startOrphanedLauncherHarness("deleted-watchdog", OrphanPhase.WORKLOAD, deleteWatchdog = true)
    try {
      orphanParentAfterPhaseStarts(mutant)
      assertWithMessage("deleting the production watchdog call must retain inherited FD9")
        .that(eventuallyAcquiresLock(mutant.lockFile))
        .isFalse()
    } finally {
      stopOrphanedLauncherHarness(mutant)
    }
  }

  @Test
  fun `orphan watchdog logs TERM and KILL delivery failures`() {
    val termFailure = runOrphanWatchdogFailureHarness(WatchdogSignalFailure.TERM)
    assertWithMessage("TERM failure output:\n%s", termFailure.output)
      .that(termFailure.exitCode)
      .isEqualTo(0)
    assertThat(termFailure.output)
      .contains("orphan watchdog failed to send TERM to controlled group")
    assertThat(termFailure.output).contains("orphan watchdog escalating controlled group to KILL")

    val killFailure = runOrphanWatchdogFailureHarness(WatchdogSignalFailure.KILL)
    assertWithMessage("KILL failure output:\n%s", killFailure.output)
      .that(killFailure.exitCode)
      .isNotEqualTo(0)
    assertThat(killFailure.output).contains("orphan watchdog sent TERM to controlled group")
    assertThat(killFailure.output).contains("orphan watchdog failed to KILL controlled group")
  }

  @Test
  fun `direct launcher mode rejects unauthenticated paths before writing`() {
    val ready = temporaryDirectory.resolve("untrusted-ready")
    val release = temporaryDirectory.resolve("untrusted-release")
    val status = temporaryDirectory.resolve("untrusted-status")
    Files.writeString(release, "invalid\n")

    val result =
      run(
        listOf(
          "/bin/bash",
          supervisor.toString(),
          "--run-controlled-launcher",
          ProcessHandle.current().pid().toString(),
          "${ProcessHandle.current().pid()}:1:1",
          ready.toString(),
          release.toString(),
          status.toString(),
          numericUid(),
          IMPLEMENTATION_SHA,
          "a".repeat(64),
        )
      )

    assertThat(result.exitCode).isNotEqualTo(0)
    assertThat(Files.exists(ready, LinkOption.NOFOLLOW_LINKS)).isFalse()
    assertThat(Files.exists(status, LinkOption.NOFOLLOW_LINKS)).isFalse()
    assertThat(Files.readString(release)).isEqualTo("invalid\n")
  }

  @Test
  fun `authenticated launcher rejects every dangling handshake symlink before writing`() {
    listOf("ready", "release", "status").forEach { artifact ->
      val result = runDanglingLauncherArtifactHarness(artifact)

      assertWithMessage("$artifact process output:\n%s", result.output)
        .that(result.exitCode)
        .isEqualTo(0)
    }
  }

  @Test
  fun `nested signal records latest status without reentering group containment`() {
    val result = runNestedContainmentSignalHarness(NestedContainmentEntry.SIGNAL_HANDLER)

    assertWithMessage("process output:\n%s", result.output).that(result.exitCode).isEqualTo(0)
  }

  @Test
  fun `nested signal during finalization cannot reenter group containment`() {
    val result = runNestedContainmentSignalHarness(NestedContainmentEntry.FINALIZER)

    assertWithMessage("process output:\n%s", result.output).that(result.exitCode).isEqualTo(129)
  }

  private fun createFixture(name: String): HandoffFixture {
    val runParent = Files.createDirectories(temporaryDirectory.resolve("runs-$name")).toRealPath()
    val runRoot = Files.createDirectory(runParent.resolve("cs2a.Test123"))
    Files.setPosixFilePermissions(runRoot, MODE_0700)
    val meta = Files.createDirectory(runRoot.resolve("meta"))
    Files.setPosixFilePermissions(meta, MODE_0700)
    val stateParent =
      Files.createDirectories(temporaryDirectory.resolve("remote-state-$name")).toRealPath()
    val state = Files.createDirectory(stateParent.resolve("governor-state.Test1234"))
    Files.setPosixFilePermissions(state, MODE_0700)
    val lockFile = Files.createFile(stateParent.resolve("task13.lock"))
    Files.setPosixFilePermissions(lockFile, MODE_0600)
    val implementationFile = temporaryDirectory.resolve("implementation-$name.txt")
    Files.writeString(implementationFile, "$IMPLEMENTATION_SHA\n")
    val controlledUidFile = temporaryDirectory.resolve("controlled-uid-$name.txt")
    Files.writeString(controlledUidFile, "${numericUid()}\n")
    val runnerFile = writeExecutable(temporaryDirectory.resolve("runner-$name.sh"), "exit 0\n")
    val testStat = writeTestStat(temporaryDirectory.resolve("test-stat-$name"))
    val runnableSupervisor = temporaryDirectory.resolve("supervisor-$name.sh")
    Files.writeString(
      runnableSupervisor,
      Files.readString(supervisor)
        .replace(PRODUCTION_RUN_PARENT, runParent.toString())
        .replace(PRODUCTION_STATE_PARENT, stateParent.toString())
        .replace(PRODUCTION_LOCK_FILE, lockFile.toString())
        .replace(
          "/opt/revoman-benchmark/cs2a-implementation-sha",
          implementationFile.toString(),
        )
        .replace("/opt/revoman-benchmark/controlled-uid", controlledUidFile.toString())
        .replace("/opt/revoman-benchmark/cs2a-controlled-run.sh", runnerFile.toString())
        .replace(
          "require_root_file \"${'$'}0\" 555",
          "require_root_file ${quote(runnableSupervisor)} 555",
        )
        .replace("sha256sum \"${'$'}0\"", "sha256sum ${quote(runnableSupervisor)}"),
    )
    val runnerSha = sha256(runnerFile)
    val supervisorSha = sha256(runnableSupervisor)
    CORE_STATE_FILES.forEach { fileName ->
      val content =
        when (fileName) {
          "child-or-supervisor-status.txt" -> "0\n"
          "restoration-failed.txt",
          "containment-failed.txt" -> "false\n"
          "finished-at.txt" -> "2026-08-13T00:00:00+00:00\n"
          "original-governors.tsv",
          "restored-governors.tsv" ->
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor\tpowersave\n"
          "executed-script-sha256sums.tsv" -> "runner\t$runnerSha\nsupervisor\t$supervisorSha\n"
          "authenticated-handoff.tsv" ->
            "implementation\t$IMPLEMENTATION_SHA\nuid\t${numericUid()}\n" +
              "runner\t$runnerSha\nsupervisor\t$supervisorSha\n"
          "run-root.txt" -> "$runRoot\n"
          "implementation-sha.txt" -> "$IMPLEMENTATION_SHA\n"
          "lock-provenance.txt" -> "$LOCK_PROVENANCE\n"
          else -> "$name:$fileName\n"
        }
      writeRootStateFile(state.resolve(fileName), content)
    }
    return HandoffFixture(
      state,
      runRoot,
      meta,
      meta.resolve("supervisor-core"),
      meta.resolve("supervisor"),
      runnableSupervisor,
      lockFile,
      implementationFile,
      controlledUidFile,
      runnerFile,
      testStat,
    )
  }

  private fun runControlledChildHarness(
    source: String,
    name: String,
    substituteLock: Boolean = false,
  ): ProcessResult {
    val root = Files.createDirectories(temporaryDirectory.resolve("child-$name"))
    val lock = Files.createFile(root.resolve("task13.lock"))
    val proc = Files.createDirectory(root.resolve("proc-fd"))
    listOf("0", "1", "2", "8", "9").forEach { Files.createFile(proc.resolve(it)) }
    val output = root.resolve("environment.txt")
    val testStat = writeTestStat(root.resolve("test-stat"))
    val runner = root.resolve("runner.sh")
    Files.writeString(
      runner,
      """
      #!/bin/bash
      set -eu
      test -z "${'$'}{POISONED_SECRET+x}"
      test ! -e /dev/fd/8
      test -e /dev/fd/9
      test "${'$'}(env | sed -n 's/^CS2A_[^=]*=.*/x/p' | wc -l | tr -d ' ')" = 4
      test "${'$'}PATH" = /usr/bin:/bin
      env | LC_ALL=C sort >${quote(output)}
      """
        .trimIndent() + "\n",
    )
    runner.toFile().setExecutable(true, false)
    val runnable = root.resolve("supervisor.sh")
    Files.writeString(
      runnable,
      source
        .replace("readonly LOCK_FILE=$PRODUCTION_LOCK_FILE", "readonly LOCK_FILE=${lock}")
        .replace(
          "readonly RUNNER_FILE=/opt/revoman-benchmark/cs2a-controlled-run.sh",
          "readonly RUNNER_FILE=${runner}",
        )
        .replace("readonly PROC_FD_ROOT=/proc/self/fd", "readonly PROC_FD_ROOT=${proc}"),
    )
    val harness =
      """
      source "${'$'}1"
      SUBSTITUTE=${'$'}2
      TEST_STAT=${'$'}3
      stat() {
        if test "${'$'}1" = -Lc && test "${'$'}2" = '%d:%i'; then
          case "${'$'}3" in
            "${'$'}PROC_FD_ROOT/9")
              if test "${'$'}SUBSTITUTE" = true; then printf '1:999999\n';
              else "${'$'}TEST_STAT" device-inode "${'$'}LOCK_FILE"; fi ;;
            "${'$'}LOCK_FILE") "${'$'}TEST_STAT" device-inode "${'$'}LOCK_FILE" ;;
            *) return 64 ;;
          esac
        else
          command /usr/bin/stat "${'$'}@"
        fi
      }
      exec 8<"${'$'}LOCK_FILE"
      exec 9<>"${'$'}LOCK_FILE"
      export POISONED_SECRET=must-not-cross-boundary
      controlled_child_exec "${'$'}(/usr/bin/id -u)" \
        $IMPLEMENTATION_SHA "${"a".repeat(64)}"
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "child-harness",
        runnable.toString(),
        substituteLock.toString(),
        testStat.toString(),
      )
    )
  }

  private fun runPreReentryHarness(source: String, name: String): ProcessResult {
    val root = Files.createDirectories(temporaryDirectory.resolve("reentry-$name"))
    val stateParent = Files.createDirectory(root.resolve("remote-state"))
    val state = Files.createDirectory(stateParent.resolve("governor-state.Test1234"))
    val lock = Files.createFile(stateParent.resolve("task13.lock"))
    val childOutput = state.resolve("child-output.log")
    val poisonMarker = root.resolve("bash-env-executed")
    val boundaryMarker = root.resolve("runuser-boundary-observed")
    val runner = root.resolve("runner.sh")
    Files.writeString(
      runner,
      """
      #!/bin/bash
      set -eu
      test ! -e ${quote(poisonMarker)}
      test -z "${'$'}{BASH_ENV+x}"
      test -z "${'$'}{POISONED_SECRET+x}"
      test ! -e /dev/fd/8
      test -e /dev/fd/9
      test "${'$'}(env | sed -n 's/^CS2A_[^=]*=.*/x/p' | wc -l | tr -d ' ')" = 4
      test "${'$'}PATH" = /usr/bin:/bin
      printf 'RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.Reentry123\n'
      """
        .trimIndent() + "\n",
    )
    runner.toFile().setExecutable(true, false)
    val poison = root.resolve("poison-bash-env.sh")
    Files.writeString(poison, "printf poisoned >${quote(poisonMarker)}\n")
    val mockSetsid = writeExecutable(root.resolve("setsid"), "exec \"${'$'}@\"\n")
    val mockTimeout =
      writeExecutable(
        root.resolve("timeout"),
        """
        test "${'$'}1" = --foreground
        test "${'$'}2" = --signal=TERM
        test "${'$'}3" = --kill-after=30
        test "${'$'}4" = 43200
        shift 4
        exec "${'$'}@"
        """
          .trimIndent() + "\n",
      )
    val expectedUid = ProcessHandle.current().info().user().orElse("")
    assertThat(expectedUid).isNotEmpty()
    val numericUid = run(listOf("/usr/bin/id", "-u")).output.trim()
    val mockRunuser =
      writeExecutable(
        root.resolve("runuser"),
        """
        test "${'$'}1" = -u
        test "${'$'}2" = gopala.akshintala
        test "${'$'}3" = --
        shift 3
        test "${'$'}#" -eq 13
        test "${'$'}1" = /usr/bin/env
        test "${'$'}2" = -i
        test "${'$'}3" = PATH=/usr/bin:/bin
        test "${'$'}4" = CS2A_LOCK_FD=9
        test "${'$'}5" = CS2A_AUTHENTICATED_UID=$numericUid
        test "${'$'}6" = CS2A_IMPLEMENTATION_SHA=$IMPLEMENTATION_SHA
        test "${'$'}7" = CS2A_AUTHENTICATED_RUNNER_SHA=${"a".repeat(64)}
        test "${'$'}8" = /bin/bash
        test "${'$'}9" = ${quote(root.resolve("supervisor.sh"))}
        test "${'$'}{10}" = --run-controlled-child
        test "${'$'}{11}" = $numericUid
        test "${'$'}{12}" = $IMPLEMENTATION_SHA
        test "${'$'}{13}" = ${"a".repeat(64)}
        printf observed >${quote(boundaryMarker)}
        export BASH_ENV=${quote(poison)}
        export POISONED_SECRET=must-not-cross-reentry
        exec "${'$'}@"
        """
          .trimIndent() + "\n",
      )
    val mockStat =
      writeExecutable(
        root.resolve("stat"),
        """
        test "${'$'}1" = -Lc
        case "${'$'}2:${'$'}3" in
          %d:%i:/dev/fd/9|%d:%i:${quote(lock)}) printf '1:424242\n' ;;
          %u:%g:%a:${quote(state)}) printf '0:0:700\n' ;;
          *) exit 64 ;;
        esac
        """
          .trimIndent() + "\n",
      )
    val runnable = root.resolve("supervisor.sh")
    Files.writeString(
      runnable,
      withDeterministicProcessIdentity(source)
        .replace("readonly LOCK_FILE=$PRODUCTION_LOCK_FILE", "readonly LOCK_FILE=$lock")
        .replace(
          "readonly STATE_PARENT=$PRODUCTION_STATE_PARENT",
          "readonly STATE_PARENT=$stateParent",
        )
        .replace(
          "readonly RUNNER_FILE=/opt/revoman-benchmark/cs2a-controlled-run.sh",
          "readonly RUNNER_FILE=$runner",
        )
        .replace("readonly PROC_FD_ROOT=/proc/self/fd", "readonly PROC_FD_ROOT=/dev/fd")
        .replace("/usr/bin/setsid", mockSetsid.toString())
        .replace("/usr/bin/timeout", mockTimeout.toString())
        .replace("/usr/sbin/runuser", mockRunuser.toString())
        .replace("stat -Lc", "${quote(mockStat)} -Lc"),
    )
    val sourceAlias = root.resolve("supervisor-source-alias.sh")
    Files.createSymbolicLink(sourceAlias, runnable)
    val harness =
      """
      source "${'$'}1"
      exec 8<>"${'$'}2"
      exec 9<>"${'$'}2"
      kill() {
        if test "${'$'}#" -eq 3 && test "${'$'}2" = -- \
          && test "${'$'}3" = "-${'$'}CHILD_PGID"; then
          case "${'$'}1" in
            -0) /bin/kill -0 "${'$'}CHILD_PID" ;;
            -TERM) /bin/kill -TERM "${'$'}CHILD_PID" ;;
            -KILL) /bin/kill -KILL "${'$'}CHILD_PID" ;;
            *) return 64 ;;
          esac
        else
          /bin/kill "${'$'}@"
        fi
      }
      launch_controlled_child "${'$'}(/usr/bin/id -u)" \
        $IMPLEMENTATION_SHA ${"a".repeat(64)} "${'$'}3"
      wait_for_controlled_child
      test -f "${'$'}4"
      test ! -e "${'$'}5"
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        runnable.toString(),
        sourceAlias.toString(),
        lock.toString(),
        childOutput.toString(),
        boundaryMarker.toString(),
        poisonMarker.toString(),
      )
    )
  }

  private fun writeExecutable(path: Path, body: String): Path {
    Files.writeString(path, "#!/bin/sh\nset -eu\n$body")
    path.toFile().setExecutable(true, false)
    return path
  }

  private fun writeTestStat(path: Path): Path =
    writeExecutable(
      path,
      """
      field=${'$'}1
      path=${'$'}2
      case "${'$'}(/usr/bin/uname -s)" in
        Darwin)
          case "${'$'}field" in
            uid) /usr/bin/stat -f '%u' "${'$'}path" ;;
            gid) /usr/bin/stat -f '%g' "${'$'}path" ;;
            mode) /usr/bin/stat -f '%Lp' "${'$'}path" ;;
            device-inode) /usr/bin/stat -f '%d:%i' "${'$'}path" ;;
            *) exit 64 ;;
          esac
          ;;
        *)
          case "${'$'}field" in
            uid) stat -c '%u' "${'$'}path" ;;
            gid) stat -c '%g' "${'$'}path" ;;
            mode) stat -c '%a' "${'$'}path" ;;
            device-inode) stat -c '%d:%i' "${'$'}path" ;;
            *) exit 64 ;;
          esac
          ;;
      esac
      """
        .trimIndent() + "\n",
    )

  private fun mutateFinalEvidence(fixture: HandoffFixture, name: String, content: String) {
    writeRootStateFile(fixture.state.resolve(name), content)
    writeRootStateFile(fixture.finalDestination.resolve(name), content)
  }

  private fun numericUid(): String = run(listOf("/usr/bin/id", "-u")).output.trim()

  private fun sha256(path: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {
      "%02x".format(it)
    }

  private fun treeSnapshot(root: Path): Map<String, String> =
    Files.walk(root).use { paths ->
      paths.sorted().toList().associate { path ->
        val relative = root.relativize(path).toString()
        val type =
          when {
            Files.isSymbolicLink(path) -> "symlink"
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> "directory"
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> "file"
            else -> "other"
          }
        val content = if (type == "file") sha256(path) else "-"
        val metadata =
          "$type:${posixMode(path).sortedBy { it.name }}:" +
            "${Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()}:$content"
        relative to metadata
      }
    }

  private fun runCopy(
    fixture: HandoffFixture,
    installFailureAfter: Int = 0,
    modelSourceAsRoot: Boolean = true,
    destinationOwnerMutation: String = "none",
  ): ProcessResult {
    val harness =
      """
      source "${'$'}1"
      STATE=${'$'}2
      RUN_ROOT=${'$'}3
      INSTALL_FAILURE_AFTER=${'$'}4
      MODEL_SOURCE_AS_ROOT=${'$'}5
      DESTINATION_OWNER_MUTATION=${'$'}6
      TEST_STAT=${'$'}7
      CONTROLLED_UID=${'$'}(/usr/bin/id -u)
      CONTROLLED_GID=${'$'}(/usr/bin/id -g)
      INSTALL_FILE_COUNT=0

      readlink() {
        if test "${'$'}1" = -f; then
          shift
          if test "${'$'}1" = --; then shift; fi
          (cd "${'$'}1" && pwd -P)
        else
          command /usr/bin/readlink "${'$'}@"
        fi
      }

      stat() {
        if test "${'$'}1" = -c; then
          local format=${'$'}2 path=${'$'}3 uid gid mode
          uid=${'$'}("${'$'}TEST_STAT" uid "${'$'}path") || return 1
          gid=${'$'}("${'$'}TEST_STAT" gid "${'$'}path") || return 1
          mode=${'$'}("${'$'}TEST_STAT" mode "${'$'}path") || return 1
          case "${'$'}path" in
            "${'$'}STATE"/*)
              if test "${'$'}MODEL_SOURCE_AS_ROOT" = true; then
                uid=0
                gid=0
              fi
              ;;
            "${'$'}RUN_ROOT"/meta/supervisor-core/*)
              if test "${'$'}DESTINATION_OWNER_MUTATION" = file; then uid=999999; fi
              ;;
            "${'$'}RUN_ROOT"/meta/supervisor-core)
              if test "${'$'}DESTINATION_OWNER_MUTATION" = directory; then uid=999999; fi
              ;;
          esac
          case "${'$'}format" in
            '%u') printf '%s\n' "${'$'}uid" ;;
            '%u:%g:%a') printf '%s:%s:%s\n' "${'$'}uid" "${'$'}gid" "${'$'}mode" ;;
            *) return 64 ;;
          esac
        else
          command /usr/bin/stat "${'$'}@"
        fi
      }

      install() {
        if test "${'$'}1" != -d; then
          INSTALL_FILE_COUNT=${'$'}((INSTALL_FILE_COUNT + 1))
          if test "${'$'}INSTALL_FAILURE_AFTER" -gt 0 \
            && test "${'$'}INSTALL_FILE_COUNT" -eq "${'$'}INSTALL_FAILURE_AFTER"; then
            return 74
          fi
        fi
        command /usr/bin/install "${'$'}@"
      }

      mv() {
        if test "${'$'}1" = -Tn && test "${'$'}2" = --; then
          if test -e "${'$'}4" || test -L "${'$'}4"; then return 0; fi
          command /bin/mv "${'$'}3" "${'$'}4"
        else
          command /bin/mv "${'$'}@"
        fi
      }

      if copy_final_state_to_run_root "${'$'}RUN_ROOT"; then
        COPY_STATUS=0
      else
        COPY_STATUS=${'$'}?
      fi
      exit "${'$'}COPY_STATUS"
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "handoff-harness",
        fixture.runnableSupervisor.toString(),
        fixture.state.toString(),
        fixture.runRoot.toString(),
        installFailureAfter.toString(),
        modelSourceAsRoot.toString(),
        destinationOwnerMutation,
        fixture.testStat.toString(),
      )
    )
  }

  private fun runPublishFinal(
    fixture: HandoffFixture,
    stateOverride: Path = fixture.state,
    lockAvailable: Boolean = true,
    substituteLockFd: Boolean = false,
    installFailureAfter: Int = 0,
    destinationOwnerMutation: String = "none",
  ): ProcessResult {
    val harness =
      publishFinalHarness +
        """

        publish_final_handoff_main "${'$'}2" "${'$'}3"
        """
          .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "publish-final-harness",
        fixture.runnableSupervisor.toString(),
        fixture.runRoot.toString(),
        stateOverride.toString(),
        fixture.lockFile.toString(),
        lockAvailable.toString(),
        substituteLockFd.toString(),
        fixture.testStat.toString(),
        installFailureAfter.toString(),
        destinationOwnerMutation,
      )
    )
  }

  private fun completeFinalHandoff(fixture: HandoffFixture) {
    writeRootStateFile(fixture.state.resolve("operator-post-supervisor-exit.txt"), "0\n")
    assertProcessSucceeds(runCopy(fixture))
    assertProcessSucceeds(runPublishFinal(fixture))
  }

  private fun runValidateFinal(fixture: HandoffFixture): ProcessResult {
    val harness =
      publishFinalHarness +
        """

        flock() { return 97; }
        install() { return 97; }
        mktemp() { return 97; }
        mv() { return 97; }
        ln() { return 97; }
        chown() { return 97; }
        chmod() { return 97; }
        launch_controlled_child() { return 97; }
        authenticate_released_lock() { return 97; }
        supervisor_dispatch --validate-final-handoff "${'$'}2" "${'$'}3"
        """
          .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "validate-final-harness",
        fixture.runnableSupervisor.toString(),
        fixture.runRoot.toString(),
        fixture.state.toString(),
        fixture.lockFile.toString(),
        "true",
        "false",
        fixture.testStat.toString(),
        "0",
        "none",
      )
    )
  }

  private fun preparePublication(fixture: HandoffFixture, kind: PublicationKind) {
    if (kind == PublicationKind.FINAL) {
      writeRootStateFile(fixture.state.resolve("operator-post-supervisor-exit.txt"), "0\n")
      assertProcessSucceeds(runCopy(fixture))
    }
  }

  private fun publish(
    fixture: HandoffFixture,
    kind: PublicationKind,
    destinationOwnerMutation: String = "none",
  ): ProcessResult =
    when (kind) {
      PublicationKind.CORE -> runCopy(fixture, destinationOwnerMutation = destinationOwnerMutation)
      PublicationKind.FINAL ->
        runPublishFinal(
          fixture,
          destinationOwnerMutation = destinationOwnerMutation,
        )
    }

  private fun publicationDestination(fixture: HandoffFixture, kind: PublicationKind): Path =
    when (kind) {
      PublicationKind.CORE -> fixture.destination
      PublicationKind.FINAL -> fixture.finalDestination
    }

  private fun publicationFiles(kind: PublicationKind): List<String> =
    when (kind) {
      PublicationKind.CORE -> CORE_STATE_FILES
      PublicationKind.FINAL -> FINAL_STATE_FILES
    }

  private fun assertPublicationRejected(
    fixture: HandoffFixture,
    kind: PublicationKind,
    description: String,
  ) {
    val result = publish(fixture, kind)
    assertWithMessage("${kind.name} $description\n${result.output}")
      .that(result.exitCode)
      .isNotEqualTo(0)
  }

  private fun runTerminationHarness(scenario: TerminationScenario): ProcessResult {
    val harness =
      """
      source "${'$'}1"
      SCENARIO=${'$'}2
      PROBES=0
      jobs() {
        test "${'$'}1" = -p
        printf '4242\n'
      }
      ps() {
        test "${'$'}1" = -o
        test "${'$'}2" = stat=
        test "${'$'}3" = -p
        test "${'$'}4" = 4242
        printf 'T\n'
      }
      wait() { test "${'$'}1" = 4242; }
      sleep() { :; }
      kill() {
        if test "${'$'}#" -eq 2 && test "${'$'}1" = -STOP && test "${'$'}2" = 4242; then
          return 0
        fi
        case "${'$'}1" in
          -0)
            PROBES=${'$'}((PROBES + 1))
            case "${'$'}SCENARIO" in
              DISAPPEARS_AFTER_KILL) test "${'$'}PROBES" -lt 3 ;;
              *) return 0 ;;
            esac
            ;;
          -TERM) test "${'$'}SCENARIO" != TERM_FAILURE ;;
          -KILL) test "${'$'}SCENARIO" != KILL_FAILURE ;;
          *) return 64 ;;
        esac
      }
      terminate_child_group 4242 4242
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "termination-harness",
        supervisor.toString(),
        scenario.name,
      )
    )
  }

  private fun runPreLaunchSignalHarness(state: Path, launchMarker: Path): ProcessResult {
    val mockSetsid =
      writeExecutable(
        temporaryDirectory.resolve("pre-launch-setsid"),
        "printf launched >${quote(launchMarker)}\nexit 0\n",
      )
    val script = temporaryDirectory.resolve("pre-launch-supervisor.sh")
    Files.writeString(
      script,
      Files.readString(supervisor).replace("/usr/bin/setsid", mockSetsid.toString()),
    )
    val harness =
      """
      source "${'$'}1"
      STATE=${'$'}2
      trap finalize_supervisor EXIT
      trap 'handle_signal INT 130' INT
      trap 'handle_signal TERM 143' TERM
      trap 'handle_signal HUP 129' HUP
      handle_signal TERM 143
      launch_controlled_child "${'$'}(/usr/bin/id -u)" \
        $IMPLEMENTATION_SHA ${"a".repeat(64)} "${'$'}STATE/child-output.log"
      exit 98
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "pre-launch-signal-harness",
        script.toString(),
        state.toString(),
      )
    )
  }

  private fun runLaunchAssignmentRaceHarness(progressMarker: Path): ProcessResult {
    val mockSetsid =
      writeExecutable(
        temporaryDirectory.resolve("launch-race-setsid"),
        "exec \"${'$'}@\"\n",
      )
    val mockTimeout =
      writeExecutable(
        temporaryDirectory.resolve("launch-race-timeout"),
        "printf progressed >${quote(progressMarker)}\n",
      )
    val script = temporaryDirectory.resolve("launch-race-supervisor.sh")
    Files.writeString(
      script,
      withDeterministicProcessIdentity(Files.readString(supervisor))
        .replace("/usr/bin/setsid", mockSetsid.toString())
        .replace("/usr/bin/timeout", mockTimeout.toString()),
    )
    val sourceAlias = temporaryDirectory.resolve("launch-race-supervisor-source.sh")
    Files.createSymbolicLink(sourceAlias, script)
    val harness =
      """
      source "${'$'}1"
      kill() {
        if test "${'$'}#" -eq 3 && test "${'$'}2" = -- \
          && test "${'$'}3" = "-${'$'}CHILD_PGID"; then
          case "${'$'}1" in
            -0) /bin/kill -0 "${'$'}CHILD_PID" ;;
            -TERM) /bin/kill -TERM "${'$'}CHILD_PID" ;;
            -KILL) /bin/kill -KILL "${'$'}CHILD_PID" ;;
            *) return 64 ;;
          esac
        else
          /bin/kill "${'$'}@"
        fi
      }
      install_supervisor_signal_traps
      set -T
      trap '
        if [[ "${'$'}BASH_COMMAND" == CHILD_PID=* ]]; then
          trap - DEBUG
          handle_signal TERM 143
        fi
      ' DEBUG
      if launch_controlled_child "${'$'}(/usr/bin/id -u)" \
        $IMPLEMENTATION_SHA ${"a".repeat(64)} "${'$'}2"; then
        exit 98
      else
        test "${'$'}?" -eq 1
      fi
      test "${'$'}CONTAINMENT_FAILED" = false
      test -z "${'$'}CHILD_PID"
      test -z "${'$'}CHILD_PGID"
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        script.toString(),
        sourceAlias.toString(),
        temporaryDirectory.resolve("launch-race-child.log").toString(),
      )
    )
  }

  private fun runReadyReleaseSignalRaceHarness(progressMarker: Path): ProcessResult {
    val stateParent = Files.createDirectory(temporaryDirectory.resolve("ready-edge-state"))
    val state = Files.createDirectory(stateParent.resolve("governor-state.Test1234"))
    val lock = Files.createFile(stateParent.resolve("task13.lock"))
    val childOutput = state.resolve("child-output.log")
    val mockSetsid =
      writeExecutable(temporaryDirectory.resolve("ready-edge-setsid"), "exec \"${'$'}@\"\n")
    val mockTimeout =
      writeExecutable(
        temporaryDirectory.resolve("ready-edge-timeout"),
        "printf progressed >${quote(progressMarker)}\n",
      )
    val testStat = temporaryDirectory.resolve("ready-edge-stat")
    Files.writeString(
      testStat,
      """
      #!/bin/sh
      set -eu
      case "${'$'}1:${'$'}2:${'$'}3" in
        -Lc:%d:%i:/dev/fd/9|-Lc:%d:%i:${quote(lock)}) printf '1:424242\n' ;;
        -Lc:%u:%g:%a:${quote(state)}) printf '0:0:700\n' ;;
        *) exit 64 ;;
      esac
      """
        .trimIndent() + "\n",
    )
    testStat.toFile().setExecutable(true, false)
    val script = temporaryDirectory.resolve("ready-edge-supervisor.sh")
    Files.writeString(
      script,
      withDeterministicProcessIdentity(Files.readString(supervisor))
        .replace("readonly LOCK_FILE=$PRODUCTION_LOCK_FILE", "readonly LOCK_FILE=$lock")
        .replace(
          "readonly STATE_PARENT=$PRODUCTION_STATE_PARENT",
          "readonly STATE_PARENT=$stateParent",
        )
        .replace("readonly PROC_FD_ROOT=/proc/self/fd", "readonly PROC_FD_ROOT=/dev/fd")
        .replace("/usr/bin/setsid", mockSetsid.toString())
        .replace("/usr/bin/timeout", mockTimeout.toString())
        .replace("stat -Lc", "${quote(testStat)} -Lc"),
    )
    val sourceAlias = temporaryDirectory.resolve("ready-edge-supervisor-source.sh")
    Files.createSymbolicLink(sourceAlias, script)
    val harness =
      """
      source "${'$'}1"
      exec 9<>"${'$'}2"
      STOP_ATTEMPTED=false
      kill() {
        if test "${'$'}#" -eq 2 && test "${'$'}1" = -STOP; then
          STOP_ATTEMPTED=true
          return 65
        fi
        /bin/kill "${'$'}@"
      }
      install_supervisor_signal_traps
      set -T
      trap '
        if [[ "${'$'}BASH_COMMAND" == '\''mv -f "${'$'}release_tmp" "${'$'}CHILD_RELEASE_FILE"'\'' ]]; then
          trap - DEBUG
          /bin/kill -TERM "${'$'}${'$'}"
        fi
      ' DEBUG
      if launch_controlled_child "${'$'}(/usr/bin/id -u)" \
        $IMPLEMENTATION_SHA ${"a".repeat(64)} "${'$'}3"; then
        launch_status=0
      else
        launch_status=${'$'}?
      fi
      launcher=${'$'}CHILD_PID
      sleep 2
      if test -n "${'$'}launcher"; then
        /bin/kill -KILL "${'$'}launcher" 2>/dev/null || :
        wait "${'$'}launcher" 2>/dev/null || :
      fi
      printf 'observed launch_status=%s signal_status=%s release=%s workload=%s\\n' \
        "${'$'}launch_status" "${'$'}SIGNAL_STATUS" \
        "${'$'}(test -e "${'$'}CHILD_RELEASE_FILE" && printf yes || printf no)" \
        "${'$'}(test -e "${'$'}4" && printf yes || printf no)"
      test "${'$'}launch_status" -eq 1
      test "${'$'}SIGNAL_STATUS" = 143
      test "${'$'}CONTAINMENT_FAILED" = true
      test "${'$'}STOP_ATTEMPTED" = true
      test "${'$'}CHILD_LAUNCH_CRITICAL" = false
      test "${'$'}CHILD_TERMINATION_ACTIVE" = false
      test "${'$'}SIGNAL_HANDLER_ACTIVE" = false
      test ! -e "${'$'}CHILD_RELEASE_FILE.tmp.${'$'}${'$'}"
      test ! -e "${'$'}CHILD_RELEASE_FILE"
      test ! -e "${'$'}4"
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        script.toString(),
        sourceAlias.toString(),
        lock.toString(),
        childOutput.toString(),
        progressMarker.toString(),
      )
    )
  }

  private fun runNestedReleaseCancellationHarness(progressMarker: Path): ProcessResult {
    val harness =
      """
      source "${'$'}1"
      CHILD_PID=4242
      CHILD_PGID=4242
      CHILD_GROUP_READY=true
      PROGRESS_MARKER=${'$'}2
      NESTED_SIGNAL_DELIVERED=false
      terminate_child_group() {
        test "${'$'}CHILD_TERMINATION_ACTIVE" = true || return 66
        NESTED_SIGNAL_DELIVERED=true
        handle_signal HUP 129
        return 65
      }
      chmod() {
        /bin/kill -TERM "${'$'}${'$'}"
        /bin/chmod "${'$'}@"
      }
      install_supervisor_signal_traps
      CHILD_RELEASE_FILE=${'$'}PROGRESS_MARKER.release
      if publish_controlled_child_release "${'$'}PROGRESS_MARKER"; then
        probe_status=0
      else
        probe_status=${'$'}?
      fi
      printf 'observed probe_status=%s signal_status=%s containment=%s progressed=%s nested=%s\n' \
        "${'$'}probe_status" "${'$'}SIGNAL_STATUS" "${'$'}CONTAINMENT_FAILED" \
        "${'$'}(test -e "${'$'}PROGRESS_MARKER" && printf yes || printf no)" \
        "${'$'}NESTED_SIGNAL_DELIVERED"
      test "${'$'}probe_status" -eq 1
      test "${'$'}SIGNAL_STATUS" = 129
      test "${'$'}CONTAINMENT_FAILED" = true
      test "${'$'}NESTED_SIGNAL_DELIVERED" = true
      test "${'$'}CHILD_TERMINATION_ACTIVE" = false
      test "${'$'}CHILD_LAUNCH_CRITICAL" = false
      rm -f -- "${'$'}PROGRESS_MARKER"
      test ! -e "${'$'}PROGRESS_MARKER"
      test ! -e "${'$'}CHILD_RELEASE_FILE"
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "nested-release-cancellation-harness",
        supervisor.toString(),
        progressMarker.toString(),
      )
    )
  }

  private fun runNestedTerminationGuardHarness(): ProcessResult {
    val harness =
      """
      source "${'$'}1"
      CHILD_PID=4242
      CHILD_PGID=4242
      CHILD_GROUP_READY=true
      CHILD_LAUNCH_CRITICAL=true
      NESTED_SIGNAL_DELIVERED=false
      terminate_child_group() {
        test "${'$'}CHILD_TERMINATION_ACTIVE" = true || return 66
        NESTED_SIGNAL_DELIVERED=true
        handle_signal HUP 129
        return 65
      }
      install_supervisor_signal_traps
      termination_probe() {
        /bin/kill -TERM "${'$'}${'$'}"
      }
      if termination_probe; then
        probe_status=0
      else
        probe_status=${'$'}?
      fi
      CHILD_LAUNCH_CRITICAL=false
      printf 'observed probe_status=%s signal_status=%s active=%s containment=%s nested=%s\n' \
        "${'$'}probe_status" "${'$'}SIGNAL_STATUS" "${'$'}CHILD_TERMINATION_ACTIVE" \
        "${'$'}CONTAINMENT_FAILED" "${'$'}NESTED_SIGNAL_DELIVERED"
      test "${'$'}NESTED_SIGNAL_DELIVERED" = true
      test "${'$'}probe_status" -eq 1
      test "${'$'}SIGNAL_STATUS" = 129
      test "${'$'}CHILD_TERMINATION_ACTIVE" = false
      test "${'$'}CONTAINMENT_FAILED" = true
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "nested-termination-guard-harness",
        supervisor.toString(),
      )
    )
  }

  private fun runReleasePublicationFailureHarness(
    scenario: String,
    candidate: Path,
    canonical: Path,
  ): ProcessResult {
    val harness =
      """
      source "${'$'}1"
      scenario=${'$'}2
      candidate=${'$'}3
      CHILD_RELEASE_FILE=${'$'}4
      case "${'$'}scenario" in
        write) candidate=${'$'}candidate/missing/candidate ;;
        chmod) chmod() { return 67; } ;;
        rename) mv() { return 68; } ;;
        *) exit 64 ;;
      esac
      if publish_controlled_child_release "${'$'}candidate"; then
        publish_status=0
      else
        publish_status=${'$'}?
      fi
      rm -f -- "${'$'}candidate" 2>/dev/null || :
      printf 'observed scenario=%s status=%s critical=%s canonical=%s\n' \
        "${'$'}scenario" "${'$'}publish_status" "${'$'}CHILD_LAUNCH_CRITICAL" \
        "${'$'}(test -e "${'$'}CHILD_RELEASE_FILE" && printf yes || printf no)"
      test "${'$'}publish_status" -ne 0
      test "${'$'}CHILD_LAUNCH_CRITICAL" = false
      test ! -e "${'$'}CHILD_RELEASE_FILE"
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "release-publication-failure-harness",
        supervisor.toString(),
        scenario,
        candidate.toString(),
        canonical.toString(),
      )
    )
  }

  private fun runPostReapSignalHarness(): ProcessResult {
    val statusFile = temporaryDirectory.resolve("post-reap-child-status")
    Files.writeString(statusFile, "0\n")
    val harness =
      """
      source "${'$'}1"
      CHILD_PID=4242
      CHILD_PGID=4242
      CHILD_GROUP_READY=true
      CHILD_STATUS_FILE=${quote(statusFile)}
      wait() { return 0; }
      if ! declare -F wait_for_controlled_child >/dev/null; then
        wait_for_controlled_child() { wait "${'$'}CHILD_PID"; }
      fi
      kill() {
        case "${'$'}1" in
          -0) return 1 ;;
          *)
            printf 'unexpected signal of recycled identity: %s\n' "${'$'}*" >&2
            return 65
            ;;
        esac
      }
      wait_for_controlled_child
      handle_signal TERM 143
      test -z "${'$'}CHILD_PID"
      test -z "${'$'}CHILD_PGID"
      test "${'$'}CONTAINMENT_FAILED" = false
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "post-reap-signal-harness",
        supervisor.toString(),
      )
    )
  }

  private fun runPostLeaderReapDescendantHarness(): ProcessResult {
    val statusFile = temporaryDirectory.resolve("post-leader-reap-child-status")
    Files.writeString(statusFile, "0\n")
    val harness =
      """
      source "${'$'}1"
      CHILD_PID=4242
      CHILD_PGID=4242
      CHILD_GROUP_READY=true
      CHILD_STATUS_FILE=${quote(statusFile)}
      GROUP_ALIVE=true
      LEADER_REAPED=false
      STOP_SENT=false
      TERM_SENT=false
      KILL_SENT=false
      POST_REAP_PROBES=0
      jobs() { printf '4242\n'; }
      ps() {
        test "${'$'}1" = -o
        test "${'$'}2" = stat=
        test "${'$'}3" = -p
        test "${'$'}4" = 4242
        test "${'$'}STOP_SENT" = true && printf 'T\n' || printf 'S\n'
      }
      wait() {
        test "${'$'}1" = 4242
        LEADER_REAPED=true
      }
      sleep() { :; }
      kill() {
        if test "${'$'}#" -eq 2 && test "${'$'}1" = -STOP && test "${'$'}2" = 4242; then
          test "${'$'}LEADER_REAPED" = false || return 65
          STOP_SENT=true
          return 0
        fi
        test "${'$'}2" = --
        test "${'$'}3" = -4242
        case "${'$'}1" in
          -0)
            if test "${'$'}LEADER_REAPED" = true; then
              POST_REAP_PROBES=${'$'}((POST_REAP_PROBES + 1))
            fi
            test "${'$'}GROUP_ALIVE" = true
            ;;
          -TERM)
            test "${'$'}STOP_SENT" = true || return 65
            test "${'$'}LEADER_REAPED" = false || return 65
            TERM_SENT=true
            ;;
          -KILL)
            test "${'$'}STOP_SENT" = true || return 65
            test "${'$'}LEADER_REAPED" = false || return 65
            KILL_SENT=true
            GROUP_ALIVE=false
            ;;
          *) return 64 ;;
        esac
      }
      wait_for_controlled_child
      test "${'$'}STOP_SENT" = true
      test "${'$'}TERM_SENT" = true
      test "${'$'}KILL_SENT" = true
      test "${'$'}GROUP_ALIVE" = false
      test "${'$'}LEADER_REAPED" = true
      test "${'$'}POST_REAP_PROBES" -gt 0
      test -z "${'$'}CHILD_PID"
      test -z "${'$'}CHILD_PGID"
      test "${'$'}CONTAINMENT_FAILED" = false
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "post-leader-reap-descendant-harness",
        supervisor.toString(),
      )
    )
  }

  private fun runReadyOwnershipLossHarness(progressMarker: Path): ProcessResult {
    val mockSetsid =
      writeExecutable(
        temporaryDirectory.resolve("ready-owner-setsid"),
        "printf '%s\\n' \"${'$'}${'$'}\" >\"${'$'}5\"\n",
      )
    val script = temporaryDirectory.resolve("ready-owner-supervisor.sh")
    Files.writeString(
      script,
      Files.readString(supervisor).replace("/usr/bin/setsid", mockSetsid.toString()),
    )
    val childOutput = temporaryDirectory.resolve("ready-owner-child-output.log")
    val harness =
      """
      source "${'$'}1"
      terminate_unready_child() {
        clear_child_pid_identity "${'$'}1"
        clear_child_group_identity "${'$'}CHILD_PGID"
      }
      if launch_controlled_child "${'$'}(/usr/bin/id -u)" \
        $IMPLEMENTATION_SHA ${"a".repeat(64)} "${'$'}2"; then
        exit 98
      fi
      test "${'$'}CHILD_GROUP_READY" = false
      test ! -e "${'$'}CHILD_RELEASE_FILE"
      test ! -e "${'$'}3"
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "ready-owner-harness",
        script.toString(),
        childOutput.toString(),
        progressMarker.toString(),
      )
    )
  }

  private fun runDanglingLauncherArtifactHarness(artifact: String): ProcessResult {
    val stateParent = Files.createDirectory(temporaryDirectory.resolve("dangling-$artifact-state"))
    val state = Files.createDirectory(stateParent.resolve("governor-state.Test1234"))
    val lock = Files.createFile(stateParent.resolve("task13.lock"))
    val ready = state.resolve("child-output.log.group-ready")
    val release = state.resolve("child-output.log.group-release")
    val status = state.resolve("child-output.log.child-status")
    val artifactPath =
      when (artifact) {
        "ready" -> ready
        "release" -> release
        "status" -> status
        else -> error("unknown launcher artifact: $artifact")
      }
    val missingTarget = state.resolve("missing-$artifact-target")
    Files.createSymbolicLink(artifactPath, missingTarget)
    val testStat = temporaryDirectory.resolve("dangling-$artifact-stat")
    Files.writeString(
      testStat,
      """
      #!/bin/sh
      set -eu
      case "${'$'}1:${'$'}2:${'$'}3" in
        -Lc:%d:%i:/dev/fd/9|-Lc:%d:%i:${quote(lock)}) printf '1:424242\n' ;;
        -Lc:%u:%g:%a:${quote(state)}) printf '0:0:700\n' ;;
        *) exit 64 ;;
      esac
      """
        .trimIndent() + "\n",
    )
    testStat.toFile().setExecutable(true, false)
    val script = temporaryDirectory.resolve("dangling-$artifact-supervisor.sh")
    Files.writeString(
      script,
      withDeterministicProcessIdentity(Files.readString(supervisor))
        .replace("readonly LOCK_FILE=$PRODUCTION_LOCK_FILE", "readonly LOCK_FILE=$lock")
        .replace(
          "readonly STATE_PARENT=$PRODUCTION_STATE_PARENT",
          "readonly STATE_PARENT=$stateParent",
        )
        .replace("readonly PROC_FD_ROOT=/proc/self/fd", "readonly PROC_FD_ROOT=/dev/fd")
        .replace("stat -Lc", "${quote(testStat)} -Lc"),
    )
    val harness =
      """
      source "${'$'}2"
      exec 9<>"${'$'}1"
      if authenticate_controlled_launcher \
        "${'$'}PPID" "${'$'}(process_identity "${'$'}PPID")" \
        "${'$'}3" "${'$'}4" "${'$'}5"; then
        exit 98
      fi
      test -L "${'$'}6"
      test ! -e "${'$'}6"
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "dangling-launcher-artifact-harness",
        lock.toString(),
        script.toString(),
        ready.toString(),
        release.toString(),
        status.toString(),
        artifactPath.toString(),
      )
    )
  }

  private fun startOrphanedLauncherHarness(
    name: String,
    phase: OrphanPhase,
    deleteWatchdog: Boolean = false,
  ): OrphanedLauncherFixture {
    val root = Files.createDirectory(temporaryDirectory.resolve("orphaned-launcher-$name"))
    val stateParent = Files.createDirectory(root.resolve("state"))
    val state = Files.createDirectory(stateParent.resolve("governor-state.Test1234"))
    val lock = Files.createFile(root.resolve("task13.lock"))
    val procRoot = Files.createDirectory(root.resolve("proc"))
    val childOutput = state.resolve("child-output.log")
    val readyFile = state.resolve("child-output.log.group-ready")
    val workloadStarted = root.resolve("workload-started")
    val parentReady = root.resolve("parent-ready")
    val mockSetsid =
      writeExecutable(
        root.resolve("setsid"),
        """
        exec /usr/bin/perl -MPOSIX -e '
          POSIX::setsid() >= 0 or die "setsid: ${'$'}!";
          exec @ARGV or die "exec: ${'$'}!";
        ' -- "${'$'}@"
        """
          .trimIndent() + "\n",
      )
    val mockTimeout =
      writeExecutable(
        root.resolve("timeout"),
        """
        printf 'started\n' >${quote(workloadStarted)}
        ${if (phase == OrphanPhase.AFTER_STATUS) "exit 0" else "while :; do sleep 3600; done"}
        """
          .trimIndent() + "\n",
      )
    val testStat = root.resolve("stat")
    Files.writeString(
      testStat,
      """
      #!/bin/sh
      set -eu
      case "${'$'}1:${'$'}2:${'$'}3" in
        -Lc:%d:%i:/dev/fd/9|-Lc:%d:%i:${quote(lock)}) printf '1:424242\n' ;;
        -Lc:%u:%g:%a:${quote(state)}) printf '0:0:700\n' ;;
        *) /usr/bin/stat "${'$'}@" ;;
      esac
      """
        .trimIndent() + "\n",
    )
    testStat.toFile().setExecutable(true, false)
    val script = root.resolve("supervisor.sh")
    Files.writeString(
      script,
      Files.readString(supervisor)
        .replace("readonly LOCK_FILE=$PRODUCTION_LOCK_FILE", "readonly LOCK_FILE=$lock")
        .replace(
          "readonly STATE_PARENT=$PRODUCTION_STATE_PARENT",
          "readonly STATE_PARENT=$stateParent",
        )
        .replace("readonly PROC_FD_ROOT=/proc/self/fd", "readonly PROC_FD_ROOT=/dev/fd")
        .replace("readonly PROCESS_STAT_ROOT=/proc", "readonly PROCESS_STAT_ROOT=$procRoot")
        .replace("/usr/bin/setsid", mockSetsid.toString())
        .replace("/usr/bin/timeout", mockTimeout.toString())
        .replace("stat -Lc", "${quote(testStat)} -Lc"),
    )
    var source = Files.readString(script)
    val watchdogCall =
      "watch_controlled_launcher_parent \"${'$'}parent_identity\" \"${'$'}${'$'}\" &"
    check(source.contains(watchdogCall))
    if (deleteWatchdog) {
      source = source.replace(watchdogCall, ": # deleted production watchdog call")
      Files.writeString(script, source)
    }
    val sourceAlias = root.resolve("supervisor-source.sh")
    Files.createSymbolicLink(sourceAlias, script)
    val harness =
      """
      source "${'$'}1"
      exec 9<>"${'$'}2"
      /usr/bin/perl -MFcntl=:flock -e 'flock(STDIN, LOCK_EX) or die' <&9
      while ! test -s "${'$'}5/${'$'}${'$'}/stat"; do sleep 0.1; done
      ${if (phase == OrphanPhase.BEFORE_RELEASE) """
      publish_controlled_child_release() {
        printf 'ready\n' >"${'$'}PARENT_READY"
        while :; do :; done
      }
      """.trimIndent() else ""}
      launch_controlled_child "${'$'}(/usr/bin/id -u)" \
        $IMPLEMENTATION_SHA ${"a".repeat(64)} "${'$'}3"
      ${if (phase == OrphanPhase.WORKLOAD) "while ! test -s \"${'$'}6\"; do sleep 0.1; done" else ""}
      ${if (phase == OrphanPhase.AFTER_STATUS) "while ! test -s \"${'$'}3.child-status\"; do sleep 0.1; done" else ""}
      printf 'ready\n' >"${'$'}4"
      while :; do :; done
      """
        .trimIndent()
    val parentLog = root.resolve("parent.log")
    val parent =
      ProcessBuilder(
          "/bin/bash",
          "-c",
          harness,
          script.toString(),
          sourceAlias.toString(),
          lock.toString(),
          childOutput.toString(),
          parentReady.toString(),
          procRoot.toString(),
          workloadStarted.toString(),
        )
        .apply {
          environment()["PARENT_READY"] = parentReady.toString()
        }
        .redirectErrorStream(true)
        .redirectOutput(parentLog.toFile())
        .start()
    Files.createDirectory(procRoot.resolve(parent.pid().toString()))
    Files.writeString(
      procRoot.resolve(parent.pid().toString()).resolve("stat"),
      "${parent.pid()} (parent with spaces) S 1 ${parent.pid()} 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 424242\n",
    )
    try {
      waitForPath(parentReady)
    } catch (failure: IllegalStateException) {
      error("${failure.message}\nparent output:\n${Files.readString(parentLog)}")
    }
    return OrphanedLauncherFixture(parent, lock, readyFile, workloadStarted, parentLog, childOutput)
  }

  private fun orphanParentAfterPhaseStarts(fixture: OrphanedLauncherFixture) {
    assertThat(canAcquireLock(fixture.lockFile)).isFalse()
    fixture.parent.destroyForcibly()
    assertThat(fixture.parent.waitFor()).isNotEqualTo(0)
    Files.deleteIfExists(
      fixture.readyFile.parent.parent.parent
        .resolve("proc")
        .resolve(fixture.parent.pid().toString())
        .resolve("stat")
    )
  }

  private fun stopOrphanedLauncherHarness(fixture: OrphanedLauncherFixture) {
    fixture.parent.destroyForcibly()
    fixture.parent.waitFor()
    if (Files.isRegularFile(fixture.readyFile)) {
      val launcherPid = Files.readString(fixture.readyFile).trim()
      run(listOf("/bin/kill", "-KILL", "-$launcherPid"))
    }
  }

  private fun waitForPath(path: Path) {
    repeat(200) {
      if (Files.isRegularFile(path)) return
      Thread.sleep(50)
    }
    error("timed out waiting for $path")
  }

  private fun eventuallyAcquiresLock(lock: Path): Boolean {
    repeat(200) {
      if (canAcquireLock(lock)) return true
      Thread.sleep(50)
    }
    return false
  }

  private fun canAcquireLock(lock: Path): Boolean =
    run(
        listOf(
          "/usr/bin/perl",
          "-MFcntl=:flock",
          "-e",
          "open(my ${'$'}f, '+<', ${'$'}ARGV[0]) or die; " +
            "exit(flock(${'$'}f, LOCK_EX | LOCK_NB) ? 0 : 1)",
          lock.toString(),
        )
      )
      .exitCode == 0

  private fun withDeterministicProcessIdentity(source: String): String {
    val anchor = "stop_owned_child_anchor() {"
    check(source.contains(anchor))
    return source.replace(
      anchor,
      """
      process_identity() { printf '%s:%s:1\n' "${'$'}1" "${'$'}1"; }

      $anchor
      """
        .trimIndent(),
    )
  }

  private fun runOrphanWatchdogFailureHarness(failure: WatchdogSignalFailure): ProcessResult {
    val harness =
      """
      source "${'$'}1"
      process_identity() { return 1; }
      sleep() { :; }
      wait() { :; }
      kill() {
        test "${'$'}2" = --
        test "${'$'}3" = -4242
        case "${'$'}1" in
          -TERM) test "${failure.name}" != TERM ;;
          -KILL) test "${failure.name}" != KILL ;;
          *) return 64 ;;
        esac
      }
      watch_controlled_launcher_parent 123:321:456 4242
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "orphan-watchdog-${failure.name.lowercase()}-failure-harness",
        supervisor.toString(),
      )
    )
  }

  private fun runNestedContainmentSignalHarness(entry: NestedContainmentEntry): ProcessResult {
    val childStatusSetup = if (entry == NestedContainmentEntry.FINALIZER) "CHILD_STATUS=0" else ""
    val invocation =
      when (entry) {
        NestedContainmentEntry.SIGNAL_HANDLER ->
          """
          handle_signal TERM 143
          test "${'$'}SIGNAL_STATUS" = 129
          test "${'$'}TERM_COUNT" -eq 1
          test "${'$'}KILL_COUNT" -eq 1
          test "${'$'}LEADER_REAPED" = true
          test -z "${'$'}CHILD_PID"
          test -z "${'$'}CHILD_PGID"
          test "${'$'}CONTAINMENT_FAILED" = false
          """
            .trimIndent()
        NestedContainmentEntry.FINALIZER -> "finalize_supervisor"
      }
    val harness =
      """
      source "${'$'}1"
      CHILD_PID=4242
      CHILD_PGID=4242
      CHILD_GROUP_READY=true
      $childStatusSetup
      GROUP_ALIVE=true
      LEADER_REAPED=false
      NESTED_SIGNAL_SENT=false
      NESTED_SIGNAL_RETURNED=false
      TERM_COUNT=0
      KILL_COUNT=0
      jobs() { printf '4242\n'; }
      ps() { printf 'T\n'; }
      wait() {
        test "${'$'}1" = 4242
        LEADER_REAPED=true
      }
      sleep() {
        if test "${'$'}NESTED_SIGNAL_SENT" = false; then
          NESTED_SIGNAL_SENT=true
          handle_signal HUP 129
          NESTED_SIGNAL_RETURNED=true
        fi
      }
      kill() {
        if test "${'$'}#" -eq 2; then
          test "${'$'}1" = -STOP
          test "${'$'}2" = 4242
          test "${'$'}LEADER_REAPED" = false
          return 0
        fi
        test "${'$'}2" = --
        test "${'$'}3" = -4242
        case "${'$'}1" in
          -0)
            if test "${'$'}LEADER_REAPED" = true; then
              test "${'$'}TERM_COUNT" -gt 1 && test "${'$'}NESTED_SIGNAL_RETURNED" = true
            else
              test "${'$'}GROUP_ALIVE" = true
            fi
            ;;
          -TERM)
            if test "${'$'}LEADER_REAPED" = true; then
              printf 'post-reap group TERM\n' >&2
              return 65
            fi
            TERM_COUNT=${'$'}((TERM_COUNT + 1))
            ;;
          -KILL)
            if test "${'$'}LEADER_REAPED" = true; then
              printf 'post-reap group KILL\n' >&2
              return 65
            fi
            KILL_COUNT=${'$'}((KILL_COUNT + 1))
            GROUP_ALIVE=false
            ;;
          *) return 64 ;;
        esac
      }
      $invocation
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "nested-containment-${entry.name.lowercase()}-harness",
        supervisor.toString(),
      )
    )
  }

  private fun runCleanupSignalHarness(state: Path): ProcessResult {
    val harness =
      """
      source "${'$'}1"
      STATE=${'$'}2
      restore_governors() {
        kill -TERM "${'$'}$"
        return 0
      }
      capture_restored_governors() { cp "${'$'}1" "${'$'}2"; }
      write_state_file() {
        local name=${'$'}1 value=${'$'}2
        printf '%s\n' "${'$'}value" >"${'$'}STATE/${'$'}name"
        chmod 0400 "${'$'}STATE/${'$'}name"
        if test "${'$'}name" = child-or-supervisor-status.txt; then
          kill -TERM "${'$'}$"
        fi
      }
      trap finalize_supervisor EXIT
      trap 'handle_signal INT 130' INT
      trap 'handle_signal TERM 143' TERM
      trap 'handle_signal HUP 129' HUP
      exit 0
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "cleanup-signal-harness",
        supervisor.toString(),
        state.toString(),
      )
    )
  }

  private fun runDispatchHarness(
    script: Path,
    fixture: HandoffFixture,
    name: String,
  ): ProcessResult {
    val observed = temporaryDirectory.resolve("publish-dispatch-observed-$name")
    val harness =
      publishFinalHarness +
        """

        publish_final_handoff_main() {
          test "${'$'}1" = "${fixture.runRoot}"
          test "${'$'}2" = "${fixture.state}"
          printf 'published\n' >"$observed"
        }
        supervisor_dispatch --publish-final-handoff "${fixture.runRoot}" "${fixture.state}"
        test -f "$observed"
        """
          .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "publish-dispatch-harness",
        script.toString(),
        fixture.runRoot.toString(),
        fixture.state.toString(),
        fixture.lockFile.toString(),
        "true",
        "false",
        fixture.testStat.toString(),
        "0",
        "none",
      )
    )
  }

  private fun runFinalizeHarness(script: Path, name: String): ProcessResult {
    val state = Files.createDirectory(temporaryDirectory.resolve("finalize-state-$name"))
    val observed = temporaryDirectory.resolve("observed-handoff-$name")
    val harness =
      """
      source "${'$'}1"
      STATE=${'$'}2
      OBSERVED=${'$'}3
      CHILD_STATUS=0
      CLEANUP_COMPLETE=false
      AUTHENTICATED_RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.CallSite123
      copy_final_state_to_run_root() {
        printf 'published\n' >"${'$'}OBSERVED"
      }
      (finalize_supervisor)
      test -f "${'$'}OBSERVED"
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "finalize-harness",
        script.toString(),
        state.toString(),
        observed.toString(),
      )
    )
  }

  private fun writeRootStateFile(path: Path, content: String) {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      Files.setPosixFilePermissions(path, MODE_0600)
    }
    Files.writeString(path, content)
    Files.setPosixFilePermissions(path, MODE_0400)
  }

  private fun hiddenStages(fixture: HandoffFixture): List<String> =
    directoryNames(fixture.meta).filter { it.startsWith(".supervisor-stage.") }

  private fun directoryNames(directory: Path): List<String> =
    Files.list(directory).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() }

  private fun posixMode(path: Path): Set<PosixFilePermission> =
    Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)

  private fun assertProcessSucceeds(result: ProcessResult) {
    assertWithMessage("process output:\n%s", result.output).that(result.exitCode).isEqualTo(0)
  }

  private fun run(command: List<String>): ProcessResult {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return ProcessResult(process.waitFor(), output)
  }

  private fun quote(path: Path): String = "'${path.toString().replace("'", "'\\''")}'"

  private data class HandoffFixture(
    val state: Path,
    val runRoot: Path,
    val meta: Path,
    val destination: Path,
    val finalDestination: Path,
    val runnableSupervisor: Path,
    val lockFile: Path,
    val implementationFile: Path,
    val controlledUidFile: Path,
    val runnerFile: Path,
    val testStat: Path,
  )

  private data class ProcessResult(val exitCode: Int, val output: String)

  private data class OrphanedLauncherFixture(
    val parent: Process,
    val lockFile: Path,
    val readyFile: Path,
    val workloadStarted: Path,
    val parentLog: Path,
    val launcherLog: Path,
  )

  private enum class PublicationKind {
    CORE,
    FINAL,
  }

  private enum class TerminationScenario {
    TERM_FAILURE,
    KILL_FAILURE,
    LINGERING,
    DISAPPEARS_AFTER_KILL,
  }

  private enum class OrphanPhase {
    BEFORE_RELEASE,
    WORKLOAD,
    AFTER_STATUS,
  }

  private enum class NestedContainmentEntry {
    SIGNAL_HANDLER,
    FINALIZER,
  }

  private enum class WatchdogSignalFailure {
    TERM,
    KILL,
  }

  private companion object {
    const val PRODUCTION_RUN_PARENT = "/opt/revoman-benchmark/runs"
    const val PRODUCTION_STATE_PARENT = "/run/revoman-cs2a"
    const val PRODUCTION_LOCK_FILE = "/opt/revoman-benchmark/task13.lock"
    const val IMPLEMENTATION_SHA = "cccccccccccccccccccccccccccccccccccccccc"
    const val LOCK_PROVENANCE = "0:0:600:16777232:424242"

    val CORE_STATE_FILES =
      listOf(
        "child-or-supervisor-status.txt",
        "restoration-failed.txt",
        "containment-failed.txt",
        "finished-at.txt",
        "original-governors.tsv",
        "restored-governors.tsv",
        "executed-script-sha256sums.tsv",
        "authenticated-handoff.tsv",
        "run-root.txt",
        "implementation-sha.txt",
        "lock-provenance.txt",
      )

    val FINAL_STATE_FILES =
      CORE_STATE_FILES + listOf("operator-post-supervisor-exit.txt", "lock-released.txt")

    val MODE_0400 = setOf(PosixFilePermission.OWNER_READ)
    val MODE_0600 = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    val MODE_0755 =
      setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_EXECUTE,
      )
    val MODE_0700 =
      setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
      )
  }

  private val supervisor =
    Path.of("docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh")
      .toAbsolutePath()
      .normalize()

  private val publishFinalHarness =
    """
    source "${'$'}1"
    HARNESS_SUPERVISOR=${'$'}1
    HARNESS_LOCK_FILE=${'$'}4
    HARNESS_LOCK_AVAILABLE=${'$'}5
    HARNESS_SUBSTITUTE_LOCK_FD=${'$'}6
    TEST_STAT=${'$'}7
    INSTALL_FAILURE_AFTER=${'$'}8
    DESTINATION_OWNER_MUTATION=${'$'}9
    INSTALL_FILE_COUNT=0
    CONTROLLED_UID=${'$'}(/usr/bin/id -u)
    CONTROLLED_GID=${'$'}(/usr/bin/id -g)

    id() {
      if test "${'$'}#" -eq 1 && test "${'$'}1" = -u; then
        printf '0\n'
      elif test "${'$'}#" -eq 2 && test "${'$'}1" = -g; then
        /usr/bin/id -g
      else
        command /usr/bin/id "${'$'}@"
      fi
    }

    cat() {
      if test "${'$'}#" -eq 1 && test "${'$'}1" = /opt/revoman-benchmark/controlled-uid; then
        /usr/bin/id -u
      else
        command /bin/cat "${'$'}@"
      fi
    }

    readlink() {
      if test "${'$'}1" = -f; then
        shift
        if test "${'$'}1" = --; then shift; fi
        (cd "${'$'}1" && pwd -P)
      else
        command /usr/bin/readlink "${'$'}@"
      fi
    }

    stat() {
      if test "${'$'}1" = -Lc; then
        local format=${'$'}2 path=${'$'}3
        test "${'$'}format" = '%u:%g:%a:%d:%i' || return 64
        case "${'$'}path" in
          "${'$'}HARNESS_LOCK_FILE") printf '%s\n' '$LOCK_PROVENANCE' ;;
          /proc/*/fd/8)
            if test "${'$'}HARNESS_SUBSTITUTE_LOCK_FD" = true; then
              printf '%s\n' '0:0:600:16777232:999999'
            else
              printf '%s\n' '$LOCK_PROVENANCE'
            fi
            ;;
          *) return 64 ;;
        esac
      elif test "${'$'}1" = -c; then
        local format=${'$'}2 path=${'$'}3 uid gid mode
        uid=${'$'}("${'$'}TEST_STAT" uid "${'$'}path") || return 1
        gid=${'$'}("${'$'}TEST_STAT" gid "${'$'}path") || return 1
        mode=${'$'}("${'$'}TEST_STAT" mode "${'$'}path") || return 1
        case "${'$'}path" in
          */governor-state.*/*)
            uid=0
            gid=0
            ;;
          */governor-state.*)
            uid=0
            gid=0
            mode=700
            ;;
          */meta/supervisor/*)
            if test "${'$'}DESTINATION_OWNER_MUTATION" = file; then uid=999999; fi
            ;;
          */meta/supervisor)
            if test "${'$'}DESTINATION_OWNER_MUTATION" = directory; then uid=999999; fi
            ;;
          "${'$'}HARNESS_LOCK_FILE")
            uid=0
            gid=0
            mode=600
            ;;
          "${'$'}IMPLEMENTATION_FILE"|"${'$'}CONTROLLED_UID_FILE")
            uid=0
            gid=0
            mode=444
            ;;
          "${'$'}RUNNER_FILE"|"${'$'}HARNESS_SUPERVISOR")
            uid=0
            gid=0
            mode=555
            ;;
        esac
        case "${'$'}format" in
          '%u') printf '%s\n' "${'$'}uid" ;;
          '%u:%g:%a') printf '%s:%s:%s\n' "${'$'}uid" "${'$'}gid" "${'$'}mode" ;;
          *) return 64 ;;
        esac
      else
        command /usr/bin/stat "${'$'}@"
      fi
    }

    install() {
      if test "${'$'}1" != -d; then
        INSTALL_FILE_COUNT=${'$'}((INSTALL_FILE_COUNT + 1))
        if test "${'$'}INSTALL_FAILURE_AFTER" -gt 0 \
          && test "${'$'}INSTALL_FILE_COUNT" -eq "${'$'}INSTALL_FAILURE_AFTER"; then
          return 74
        fi
      fi
      command /usr/bin/install "${'$'}@"
    }

    chown() {
      return 0
    }

    flock() {
      test "${'$'}1" = -n
      test "${'$'}2" = 8
      test "${'$'}HARNESS_LOCK_AVAILABLE" = true
    }

    mv() {
      if test "${'$'}1" = -Tn && test "${'$'}2" = --; then
        if test -e "${'$'}4" || test -L "${'$'}4"; then return 0; fi
        command /bin/mv "${'$'}3" "${'$'}4"
      else
        command /bin/mv "${'$'}@"
      fi
    }
    """
      .trimIndent()
}
