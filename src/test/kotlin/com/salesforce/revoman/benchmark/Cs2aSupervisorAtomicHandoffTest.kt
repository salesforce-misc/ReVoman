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
          "run-root.txt" -> "$runRoot\n"
          "implementation-sha.txt" -> "$IMPLEMENTATION_SHA\n"
          "lock-provenance.txt" -> "$LOCK_PROVENANCE\n"
          else -> "$name:$fileName\n"
        }
      writeRootStateFile(state.resolve(fileName), content)
    }
    val runnableSupervisor = temporaryDirectory.resolve("supervisor-$name.sh")
    Files.writeString(
      runnableSupervisor,
      Files.readString(supervisor)
        .replace(PRODUCTION_RUN_PARENT, runParent.toString())
        .replace(PRODUCTION_STATE_PARENT, stateParent.toString())
        .replace(PRODUCTION_LOCK_FILE, lockFile.toString()),
    )
    return HandoffFixture(
      state,
      runRoot,
      meta,
      meta.resolve("supervisor-core"),
      meta.resolve("supervisor"),
      runnableSupervisor,
      lockFile,
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
      stat() {
        if test "${'$'}1" = -Lc && test "${'$'}2" = '%d:%i'; then
          case "${'$'}3" in
            "${'$'}PROC_FD_ROOT/9")
              if test "${'$'}SUBSTITUTE" = true; then printf '1:999999\n';
              else /usr/bin/stat -f '%d:%i' "${'$'}LOCK_FILE"; fi ;;
            "${'$'}LOCK_FILE") /usr/bin/stat -f '%d:%i' "${'$'}LOCK_FILE" ;;
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
      )
    )
  }

  private fun runPreReentryHarness(source: String, name: String): ProcessResult {
    val root = Files.createDirectories(temporaryDirectory.resolve("reentry-$name"))
    val lock = Files.createFile(root.resolve("task13.lock"))
    val childOutput = root.resolve("child-output.log")
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
        "shift 3\nexec \"${'$'}@\"\n",
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
        test "${'$'}2" = %d:%i
        case "${'$'}3" in
          /dev/fd/9|${quote(lock)}) printf '1:424242\n' ;;
          *) exit 64 ;;
        esac
        """
          .trimIndent() + "\n",
      )
    val runnable = root.resolve("supervisor.sh")
    Files.writeString(
      runnable,
      source
        .replace("readonly LOCK_FILE=$PRODUCTION_LOCK_FILE", "readonly LOCK_FILE=$lock")
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
      launch_controlled_child "${'$'}(/usr/bin/id -u)" \
        $IMPLEMENTATION_SHA ${"a".repeat(64)} "${'$'}3"
      wait "${'$'}CHILD_PID"
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

  private fun runCopy(
    fixture: HandoffFixture,
    installFailureAfter: Int = 0,
    modelSourceAsRoot: Boolean = true,
  ): ProcessResult {
    val harness =
      """
      source "${'$'}1"
      STATE=${'$'}2
      RUN_ROOT=${'$'}3
      INSTALL_FAILURE_AFTER=${'$'}4
      MODEL_SOURCE_AS_ROOT=${'$'}5
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
          uid=${'$'}(/usr/bin/stat -f '%u' "${'$'}path") || return 1
          gid=${'$'}(/usr/bin/stat -f '%g' "${'$'}path") || return 1
          mode=${'$'}(/usr/bin/stat -f '%Lp' "${'$'}path") || return 1
          case "${'$'}path" in
            "${'$'}STATE"/*)
              if test "${'$'}MODEL_SOURCE_AS_ROOT" = true; then
                uid=0
                gid=0
              fi
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
      )
    )
  }

  private fun runPublishFinal(
    fixture: HandoffFixture,
    stateOverride: Path = fixture.state,
    lockAvailable: Boolean = true,
    substituteLockFd: Boolean = false,
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
  )

  private data class ProcessResult(val exitCode: Int, val output: String)

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
    HARNESS_LOCK_FILE=${'$'}4
    HARNESS_LOCK_AVAILABLE=${'$'}5
    HARNESS_SUBSTITUTE_LOCK_FD=${'$'}6
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
        uid=${'$'}(/usr/bin/stat -f '%u' "${'$'}path") || return 1
        gid=${'$'}(/usr/bin/stat -f '%g' "${'$'}path") || return 1
        mode=${'$'}(/usr/bin/stat -f '%Lp' "${'$'}path") || return 1
        case "${'$'}path" in
          */governor-state.*/*)
            uid=0
            gid=0
            ;;
          "${'$'}HARNESS_LOCK_FILE")
            uid=0
            gid=0
            mode=600
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
