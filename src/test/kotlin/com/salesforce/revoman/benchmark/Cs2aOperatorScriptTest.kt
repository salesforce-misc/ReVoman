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
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Cs2aOperatorScriptTest {
  @TempDir lateinit var temporaryDirectory: Path

  @Test
  fun `operator scripts parse with Bash 3_2 and pass ShellCheck`() {
    shellScripts.forEach { script ->
      assertThat(Files.isRegularFile(script)).isTrue()
      assertProcessSucceeds(listOf("/bin/bash", "-n", script.toString()))
      assertProcessSucceeds(listOf("shellcheck", script.toString()))
    }
  }

  @Test
  fun `checked-in manifest validator is executable and syntactically accepted by jq`() {
    assertThat(Files.isExecutable(manifestValidator)).isTrue()
    assertProcessSucceeds(listOf("jq", "-n", "-f", manifestValidator.toString()))
  }

  @Test
  fun `controlled runner requires minimal authenticated values and never traverses root state`() {
    val result = run(listOf("/bin/bash", controlledRunner.toString()))

    assertThat(result.exitCode).isNotEqualTo(0)
    assertThat(result.output).contains("authenticated controlled UID")
    val source = Files.readString(controlledRunner)
    assertThat(source).doesNotContain("CONTROLLED_UID_FILE")
    assertThat(source).doesNotContain("HANDOFF_FILE")
    assertThat(source).doesNotContain("governor-state")
    assertThat(source).contains("CS2A_AUTHENTICATED_UID")
    assertThat(source).contains("CS2A_AUTHENTICATED_RUNNER_SHA")
  }

  @Test
  fun `reviewed controlled UID policy is pinned in both privileged entry points`() {
    val expectedPolicySha = "abc4307b6eb40577163790a0c453ece3ff4bff8620c85471a35a1bd3a1aea44b"

    assertBashFunctionSucceeds(
      "controlled_uid_policy_is_provisioned && " +
        "test \"\$CONTROLLED_UID_POLICY_SHA256\" = $expectedPolicySha"
    )
    assertSupervisorFunctionSucceeds(
      "controlled_uid_policy_is_provisioned && " +
        "test \"\$CONTROLLED_UID_POLICY_SHA256\" = $expectedPolicySha"
    )
  }

  @Test
  fun `remote artifact inventories validate exact safe path sets without local artifact bytes`() {
    val archive = temporaryDirectory.resolve("archive")
    val meta = Files.createDirectories(archive.resolve("meta"))
    write(
      meta.resolve("artifact-inventory.tsv"),
      "artifacts/cold/one.jfr\t123\nartifacts/warm/two.jfr\t456\n",
    )
    write(
      meta.resolve("artifact-sha256sums.txt"),
      "${"a".repeat(64)}  artifacts/cold/one.jfr\n" + "${"b".repeat(64)}  artifacts/warm/two.jfr\n",
    )

    assertBashFunctionSucceeds("validate_artifact_inventories ${quote(archive)}")
    assertThat(Files.exists(archive.resolve("artifacts/cold/one.jfr"))).isFalse()

    val validInventory = Files.readString(meta.resolve("artifact-inventory.tsv"))
    val validHashes = Files.readString(meta.resolve("artifact-sha256sums.txt"))
    val mutations =
      listOf(
        validInventory + "artifacts/cold/one.jfr\t123\n" to validHashes,
        "artifacts/../escape.jfr\t123\n" to "${"a".repeat(64)}  artifacts/../escape.jfr\n",
        validInventory to
          "${"a".repeat(64)}  artifacts/cold/one.jfr\n" +
            "${"b".repeat(64)}  artifacts/warm/other.jfr\n",
        validInventory to
          "${"a".repeat(64)}  artifacts/cold/one.jfr\n" +
            "${"b".repeat(64)}  artifacts/cold/one.jfr\n",
      )
    mutations.forEachIndexed { index, (inventory, hashes) ->
      write(meta.resolve("artifact-inventory.tsv"), inventory)
      write(meta.resolve("artifact-sha256sums.txt"), hashes)
      assertBashFunctionFails(
        "validate_artifact_inventories ${quote(archive)}",
        "artifact mutation $index",
      )
    }
  }

  @Test
  fun `root checksum inventory excludes only its own root path`() {
    val archive = Files.createDirectories(temporaryDirectory.resolve("checksums"))
    write(archive.resolve("payload.txt"), "payload\n")
    val nested = Files.createDirectories(archive.resolve("meta"))
    write(nested.resolve("evidence-sha256sums.txt"), "remote inventory bytes\n")

    assertBashFunctionSucceeds("write_root_checksum_inventory ${quote(archive)}")

    val inventory = Files.readString(archive.resolve("evidence-sha256sums.txt"))
    assertThat(inventory).contains("  ./meta/evidence-sha256sums.txt")
    assertThat(inventory).contains("  ./payload.txt")
    assertThat(inventory).doesNotContain("  ./evidence-sha256sums.txt")
  }

  @Test
  fun `commands table and log files are a strict bijection`() {
    val archive = Files.createDirectories(temporaryDirectory.resolve("commands"))
    val meta = Files.createDirectories(archive.resolve("meta"))
    val logs = Files.createDirectories(archive.resolve("logs"))
    val validCommands = "first\t/bin/true\nsecond\t/bin/false\n"
    write(meta.resolve("commands.tsv"), validCommands)
    listOf("first", "second").forEach { label ->
      write(logs.resolve("$label.stdout"), "")
      write(logs.resolve("$label.stderr"), "")
      write(logs.resolve("$label.exit"), if (label == "first") "0\n" else "1\n")
    }

    assertBashFunctionSucceeds("validate_commands_bijection ${quote(archive)}")

    write(meta.resolve("commands.tsv"), validCommands + "first\t/bin/true\n")
    assertBashFunctionFails("validate_commands_bijection ${quote(archive)}", "duplicate label")
    write(meta.resolve("commands.tsv"), validCommands)
    Files.delete(logs.resolve("second.stderr"))
    assertBashFunctionFails("validate_commands_bijection ${quote(archive)}", "missing stderr")
  }

  @Test
  fun `command protocol pins exact ordered labels argv and stage prefixes`() {
    val fixture = createCompleteArchiveFixture("exact-command-protocol")
    val commands = fixture.archive.resolve("meta/commands.tsv")
    val rows = Files.readAllLines(commands)
    val expectedLabels =
      listOf(
        "install-harness",
        "export-baseline-a",
        "export-baseline-b",
        "export-candidate",
        "verify-manifest-baseline-a",
        "verify-manifest-baseline-b",
        "verify-manifest-candidate",
        "cold-aa",
        "warm-aa",
        "verify-aa-cold",
        "comparison-aa-cold",
        "verify-aa-warm",
        "comparison-aa-warm",
        "cold-candidate",
        "warm-candidate",
        "retained-candidate",
        "verify-candidate-cold",
        "comparison-candidate-cold",
        "verify-candidate-warm",
        "comparison-candidate-warm",
        "verify-candidate-retained",
        "comparison-candidate-retained",
      )
    assertThat(rows.map { it.substringBefore('\t') })
      .containsExactlyElementsIn(expectedLabels)
      .inOrder()
    assertThat(rows[7]).contains("\t--seed\t5928239383101656625")
    assertThat(rows[7]).contains("\t--metrics\tlatency\\,peak-rss\\,allocation")
    assertThat(rows[15]).contains("\t--mode\tretained")
    assertBashFunctionSucceeds("validate_command_protocol ${quote(fixture.archive)}")

    val mutations =
      linkedMapOf(
        "reordered rows" to listOf(rows[1], rows[0]) + rows.drop(2),
        "renamed label" to
          rows.toMutableList().also { it[0] = it[0].replaceBefore('\t', "renamed") },
        "changed argv" to
          rows.toMutableList().also { it[7] = it[7].replace("\t--blocks\t50", "\t--blocks\t51") },
        "future row" to rows + "future\t/bin/false",
      )
    mutations.forEach { (label, mutation) ->
      write(commands, mutation.joinToString("\n", postfix = "\n"))
      assertBashFunctionFails(
        "validate_command_protocol ${quote(fixture.archive)}",
        label,
      )
    }

    val stageBounds =
      linkedMapOf(
        "setup" to (0..9),
        "aa-captured" to (9..13),
        "aa-compared" to (13..16),
        "candidate-captured" to (16..22),
        "candidate-compared" to (22..22),
      )
    stageBounds.forEach { (stage, range) ->
      write(fixture.archive.resolve("meta/stage.txt"), "$stage\n")
      listOf(range.first, range.last).distinct().forEach { count ->
        write(commands, rows.take(count).joinToString("\n", postfix = if (count == 0) "" else "\n"))
        assertBashFunctionSucceeds("validate_command_protocol ${quote(fixture.archive)}")
      }
      if (range.first > 0) {
        write(commands, rows.take(range.first - 1).joinToString("\n", postfix = "\n"))
        assertBashFunctionFails(
          "validate_command_protocol ${quote(fixture.archive)}",
          "$stage short prefix",
        )
      }
      if (range.last < rows.size) {
        write(commands, rows.take(range.last + 1).joinToString("\n", postfix = "\n"))
        assertBashFunctionFails(
          "validate_command_protocol ${quote(fixture.archive)}",
          "$stage future prefix",
        )
      }
    }
  }

  @Test
  fun `runner exports manifest verification commands in locale-independent order`() {
    val source = Files.readString(controlledRunner)

    assertThat(source).contains("for manifest_name in baseline-a baseline-b candidate; do")
    assertThat(source).doesNotContain("for manifest in \"${'$'}RUN_ROOT\"/manifests/*.json; do")
  }

  @Test
  fun `archive stage schema accepts each exact partial prefix and rejects missing or future files`() {
    ARCHIVE_STAGES.forEach { stage ->
      val archive = createStageFixture("stage-$stage", stage)
      assertBashFunctionSucceeds("validate_stage_schema ${quote(archive)}")

      val unknownStatus = createStageFixture("stage-$stage-unknown-status", stage)
      write(unknownStatus.resolve("meta/comparison-future-$stage-exit.txt"), "0\n")
      assertBashFunctionFails(
        "validate_stage_schema ${quote(unknownStatus)}",
        "$stage must reject unknown status evidence",
      )
    }

    val missing = createStageFixture("stage-missing", "candidate-compared")
    Files.delete(missing.resolve("results/comparison-candidate-retained.md"))
    assertBashFunctionFails(
      "validate_stage_schema ${quote(missing)}",
      "candidate-compared must contain its complete exact result set",
    )

    val future = createStageFixture("stage-future", "aa-captured")
    write(future.resolve("results/cold-candidate.json"), "{}\n")
    assertBashFunctionFails(
      "validate_stage_schema ${quote(future)}",
      "aa-captured must reject a future-stage result",
    )
  }

  @Test
  fun `archive safety rejects symlinks fifos and device files`() {
    val regular = Files.createDirectories(temporaryDirectory.resolve("regular-tree"))
    write(regular.resolve("payload.txt"), "payload\n")
    assertBashFunctionSucceeds("validate_archive_safety ${quote(regular)}")

    val symlinkTree = Files.createDirectories(temporaryDirectory.resolve("symlink-tree"))
    Files.createSymbolicLink(symlinkTree.resolve("payload-link"), regular.resolve("payload.txt"))
    assertBashFunctionFails(
      "validate_archive_safety ${quote(symlinkTree)}",
      "archive symlink",
    )

    val fifoTree = Files.createDirectories(temporaryDirectory.resolve("fifo-tree"))
    assertProcessSucceeds(listOf("mkfifo", fifoTree.resolve("payload.fifo").toString()))
    assertBashFunctionFails("validate_archive_safety ${quote(fifoTree)}", "archive FIFO")
    assertBashFunctionFails("archive_path_type_is_safe /dev/null", "device node")
  }

  @Test
  fun `archive only rejects remote symlinks before writing local authority evidence`() {
    val destinations =
      listOf(
        "operator-supervisor.log",
        "operator-supervisor-exit.txt",
        "operator-post-supervisor-exit.txt",
        "operator-resume-validation-exit.txt",
        "local-validation-passed.txt",
        "operator-final-exit.txt",
      )

    destinations.forEachIndexed { index, destination ->
      val workspace =
        Files.createDirectories(temporaryDirectory.resolve("archive-ingress-$index")).toRealPath()
      val remote = Files.createDirectories(workspace.resolve("remote"))
      listOf("manifests", "results", "logs", "meta").forEach { directory ->
        Files.createDirectories(remote.resolve(directory))
      }
      val victim = workspace.resolve("victim-$index.txt")
      write(victim, "unchanged\n")
      Files.createSymbolicLink(remote.resolve("meta/$destination"), victim)
      write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
      write(workspace.resolve("build/cs2a-supervisor.log"), "trusted supervisor log\n")
      write(workspace.resolve("build/cs2a-supervisor-exit.txt"), "70\n")

      val harness =
        """
        source "${'$'}1"
        REMOTE_FIXTURE=${'$'}2
        RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.ArchiveIngress123
        GOVERNOR_STATE=/run/revoman-cs2a/governor-state.ArchiveIngress123
        prepare_operator_source() { LOCAL_DRIVER=/bin/false; }
        verify_remote_bundle() { return 0; }
        validate_remote_final_handoff() { return 0; }
        refresh_remote_final_handoff() { return 0; }
        ssh() {
          case "${'$'}*" in
            *readlink\ -f*) printf '%s\n' "${'$'}RUN_ROOT" ;;
            *run-root.txt*) printf '%s\n' "${'$'}RUN_ROOT" ;;
            *implementation-sha.txt*) printf '%s\n' "$IMPLEMENTATION_SHA" ;;
            *executed-script-sha256sums.tsv*)
              printf 'runner\t%s\nsupervisor\t%s\n' \
                "${'$'}(sha256_of "${'$'}CONTROLLED_RUNNER")" \
                "${'$'}(sha256_of "${'$'}SUPERVISOR")"
              ;;
            *operator-post-supervisor-exit.txt*) printf '%s\n' 70 ;;
            *) return 97 ;;
          esac
        }
        rsync() {
          test "${'$'}1" = -a || return 97
          local destination=${'$'}3 directory
          directory=${'$'}{destination%/}
          directory=${'$'}{directory##*/}
          cp -a "${'$'}REMOTE_FIXTURE/${'$'}directory/." "${'$'}destination/"
        }
        if operator_main --archive-only "${'$'}RUN_ROOT" "${'$'}GOVERNOR_STATE"; then
          exit 98
        else
          status=${'$'}?
        fi
        test "${'$'}status" -eq 70
        """
          .trimIndent()
      val result =
        run(
          listOf(
            "/bin/bash",
            "-c",
            harness,
            "archive-ingress-harness",
            operator.toString(),
            remote.toString(),
          ),
          workspace,
        )

      assertWithMessage("$destination\n${result.output}").that(result.exitCode).isEqualTo(0)
      assertThat(Files.readString(victim)).isEqualTo("unchanged\n")
      assertThat(Files.exists(workspace.resolve("build/cs2a-local-evidence-dir.txt"))).isFalse()
      assertThat(
          Files.exists(
            workspace.resolve(
              "docs/superpowers/benchmarks/results/v1/" +
                "cs2a-$IMPLEMENTATION_SHA/cs2a.ArchiveIngress123"
            )
          )
        )
        .isFalse()
    }
  }

  @Test
  fun `archive transfer failure prints only its safe retry stage`() {
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("archive-transfer-failure")).toRealPath()
    val remote = Files.createDirectories(workspace.resolve("remote"))
    listOf("manifests", "results", "logs", "meta").forEach { directory ->
      Files.createDirectories(remote.resolve(directory))
    }
    write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
    write(workspace.resolve("build/cs2a-supervisor.log"), "trusted supervisor log\n")
    write(workspace.resolve("build/cs2a-supervisor-exit.txt"), "70\n")
    val harness =
      """
      source "${'$'}1"
      REMOTE_FIXTURE=${'$'}2
      RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.TransferFailure123
      GOVERNOR_STATE=/run/revoman-cs2a/governor-state.TransferFailure123
      prepare_operator_source() { LOCAL_DRIVER=/bin/false; }
      verify_remote_bundle() { return 0; }
      validate_remote_final_handoff() { return 0; }
      ssh() {
        case "${'$'}*" in
          *readlink\ -f*) printf '%s\n' "${'$'}RUN_ROOT" ;;
          *run-root.txt*) printf '%s\n' "${'$'}RUN_ROOT" ;;
          *implementation-sha.txt*) printf '%s\n' "$IMPLEMENTATION_SHA" ;;
          *executed-script-sha256sums.tsv*)
            printf 'runner\t%s\nsupervisor\t%s\n' \
              "${'$'}(sha256_of "${'$'}CONTROLLED_RUNNER")" \
              "${'$'}(sha256_of "${'$'}SUPERVISOR")"
            ;;
          *operator-post-supervisor-exit.txt*) printf '%s\n' 70 ;;
          *) return 97 ;;
        esac
      }
      rsync() {
        count=0
        test ! -e "${'$'}PWD/build/rsync-count" || count=${'$'}(cat "${'$'}PWD/build/rsync-count")
        count=${'$'}((count + 1))
        printf '%s\n' "${'$'}count" >"${'$'}PWD/build/rsync-count"
        test "${'$'}count" -ne 2 || return 37
        local destination=${'$'}3 directory
        directory=${'$'}{destination%/}
        directory=${'$'}{directory##*/}
        cp -a "${'$'}REMOTE_FIXTURE/${'$'}directory/." "${'$'}destination/"
      }
      if operator_main --archive-only "${'$'}RUN_ROOT" "${'$'}GOVERNOR_STATE"; then
        exit 98
      else
        test "${'$'}?" -eq 70
      fi
      """
        .trimIndent()
    val result =
      run(
        listOf(
          "/bin/bash",
          "-c",
          harness,
          "archive-transfer-failure-harness",
          operator.toString(),
          remote.toString(),
        ),
        workspace,
      )
    val parent =
      workspace.resolve("docs/superpowers/benchmarks/results/v1/cs2a-$IMPLEMENTATION_SHA")
    val stages =
      Files.list(parent).use { paths ->
        paths.filter { it.fileName.toString().startsWith(".cs2a-archive-stage.") }.toList()
      }

    assertWithMessage(result.output).that(result.exitCode).isEqualTo(0)
    assertThat(stages).hasSize(1)
    assertThat(result.output).isEqualTo("LOCAL_EVIDENCE_STAGE=${stages.single()}\n")
    assertThat(Files.exists(parent.resolve("cs2a.TransferFailure123"))).isFalse()
    assertThat(Files.exists(workspace.resolve("build/cs2a-local-evidence-dir.txt"))).isFalse()
  }

  @Test
  fun `archive ingress and checksum failures print only their safe retry stage`() {
    linkedMapOf(
        "ingress" to "validate_archive_safety() { return 37; }",
        "checksum" to "write_root_checksum_inventory() { return 37; }",
      )
      .forEach { (failure, failureOverride) ->
        val workspace =
          Files.createDirectories(temporaryDirectory.resolve("archive-$failure-failure"))
            .toRealPath()
        val remote = Files.createDirectories(workspace.resolve("remote"))
        listOf("manifests", "results", "logs", "meta").forEach { directory ->
          Files.createDirectories(remote.resolve(directory))
        }
        write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
        write(workspace.resolve("build/cs2a-supervisor.log"), "trusted supervisor log\n")
        write(workspace.resolve("build/cs2a-supervisor-exit.txt"), "70\n")
        val harness =
          """
          source "${'$'}1"
          REMOTE_FIXTURE=${'$'}2
          RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.RecoveryFailure123
          GOVERNOR_STATE=/run/revoman-cs2a/governor-state.RecoveryFailure123
          prepare_operator_source() { LOCAL_DRIVER=/bin/false; }
          verify_remote_bundle() { return 0; }
          validate_remote_final_handoff() { return 0; }
          ssh() {
            case "${'$'}*" in
              *readlink\ -f*) printf '%s\n' "${'$'}RUN_ROOT" ;;
              *run-root.txt*) printf '%s\n' "${'$'}RUN_ROOT" ;;
              *implementation-sha.txt*) printf '%s\n' "$IMPLEMENTATION_SHA" ;;
              *executed-script-sha256sums.tsv*)
                printf 'runner\t%s\nsupervisor\t%s\n' \
                  "${'$'}(sha256_of "${'$'}CONTROLLED_RUNNER")" \
                  "${'$'}(sha256_of "${'$'}SUPERVISOR")"
                ;;
              *operator-post-supervisor-exit.txt*) printf '%s\n' 70 ;;
              *) return 97 ;;
            esac
          }
          rsync() {
            local destination=${'$'}3 directory
            directory=${'$'}{destination%/}
            directory=${'$'}{directory##*/}
            cp -a "${'$'}REMOTE_FIXTURE/${'$'}directory/." "${'$'}destination/"
          }
          $failureOverride
          if operator_main --archive-only "${'$'}RUN_ROOT" "${'$'}GOVERNOR_STATE"; then
            exit 98
          else
            test "${'$'}?" -eq 70
          fi
          """
            .trimIndent()
        val result =
          run(
            listOf(
              "/bin/bash",
              "-c",
              harness,
              "archive-$failure-failure-harness",
              operator.toString(),
              remote.toString(),
            ),
            workspace,
          )
        val parent =
          workspace.resolve("docs/superpowers/benchmarks/results/v1/cs2a-$IMPLEMENTATION_SHA")
        val stages =
          Files.list(parent).use { paths ->
            paths.filter { it.fileName.toString().startsWith(".cs2a-archive-stage.") }.toList()
          }

        assertWithMessage("$failure\n${result.output}").that(result.exitCode).isEqualTo(0)
        assertThat(stages).hasSize(1)
        assertThat(result.output).isEqualTo("LOCAL_EVIDENCE_STAGE=${stages.single()}\n")
        assertThat(Files.exists(parent.resolve("cs2a.RecoveryFailure123"))).isFalse()
        assertThat(Files.exists(workspace.resolve("build/cs2a-local-evidence-dir.txt"))).isFalse()
      }
  }

  @Test
  fun `archive only never overwrites remote supplied local authority destinations`() {
    val destinations =
      listOf(
        "operator-supervisor.log",
        "operator-supervisor-exit.txt",
        "operator-post-supervisor-exit.txt",
        "operator-resume-validation-exit.txt",
        "local-validation-passed.txt",
        "operator-final-exit.txt",
      )

    destinations.forEachIndexed { index, destination ->
      val workspace =
        Files.createDirectories(temporaryDirectory.resolve("archive-collision-$index")).toRealPath()
      val remote = Files.createDirectories(workspace.resolve("remote"))
      listOf("manifests", "results", "logs", "meta").forEach { directory ->
        Files.createDirectories(remote.resolve(directory))
      }
      write(remote.resolve("meta/$destination"), "remote-owned\n")
      write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
      write(workspace.resolve("build/cs2a-supervisor.log"), "trusted supervisor log\n")
      write(workspace.resolve("build/cs2a-supervisor-exit.txt"), "70\n")

      val harness =
        """
        source "${'$'}1"
        REMOTE_FIXTURE=${'$'}2
        RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.ArchiveCollision123
        GOVERNOR_STATE=/run/revoman-cs2a/governor-state.ArchiveCollision123
        prepare_operator_source() { LOCAL_DRIVER=/bin/false; }
        verify_remote_bundle() { return 0; }
        validate_remote_final_handoff() { return 0; }
        refresh_remote_final_handoff() { return 0; }
        ssh() {
          case "${'$'}*" in
            *readlink\ -f*) printf '%s\n' "${'$'}RUN_ROOT" ;;
            *run-root.txt*) printf '%s\n' "${'$'}RUN_ROOT" ;;
            *implementation-sha.txt*) printf '%s\n' "$IMPLEMENTATION_SHA" ;;
            *executed-script-sha256sums.tsv*)
              printf 'runner\t%s\nsupervisor\t%s\n' \
                "${'$'}(sha256_of "${'$'}CONTROLLED_RUNNER")" \
                "${'$'}(sha256_of "${'$'}SUPERVISOR")"
              ;;
            *operator-post-supervisor-exit.txt*) printf '%s\n' 70 ;;
            *) return 97 ;;
          esac
        }
        rsync() {
          test "${'$'}1" = -a || return 97
          local destination=${'$'}3 directory
          directory=${'$'}{destination%/}
          directory=${'$'}{directory##*/}
          cp -a "${'$'}REMOTE_FIXTURE/${'$'}directory/." "${'$'}destination/"
        }
        if operator_main --archive-only "${'$'}RUN_ROOT" "${'$'}GOVERNOR_STATE"; then
          exit 98
        else
          test "${'$'}?" -eq 70
        fi
        """
          .trimIndent()
      val result =
        run(
          listOf(
            "/bin/bash",
            "-c",
            harness,
            "archive-collision-harness",
            operator.toString(),
            remote.toString(),
          ),
          workspace,
        )
      val stageParent =
        workspace.resolve("docs/superpowers/benchmarks/results/v1/cs2a-$IMPLEMENTATION_SHA")
      val canonical = stageParent.resolve("cs2a.ArchiveCollision123")
      val stages =
        Files.list(stageParent).use { paths ->
          paths.filter { it.fileName.toString().startsWith(".cs2a-archive-stage.") }.toList()
        }

      assertWithMessage("$destination\n${result.output}").that(result.exitCode).isEqualTo(0)
      assertWithMessage(destination).that(Files.exists(canonical)).isFalse()
      assertThat(stages).hasSize(1)
      assertThat(Files.readString(stages.single().resolve("meta/$destination")))
        .isEqualTo("remote-owned\n")
      assertThat(Files.exists(workspace.resolve("build/cs2a-local-evidence-dir.txt"))).isFalse()
    }
  }

  @Test
  fun `archive and persist public modes reject injected semantic validation calls`() {
    val source = Files.readString(operator)
    val archiveCall =
      "publish_archive \"${'$'}stage\" \"${'$'}canonical\" \"${'$'}marker\" " +
        "\"${'$'}CS2A_IMPLEMENTATION_SHA\" \\\n"
    val archiveMutant =
      source.replace(
        archiveCall,
        "validate_archive_semantics \"${'$'}stage\" \"${'$'}CS2A_IMPLEMENTATION_SHA\" " +
          "/bin/false || return 70\n  $archiveCall",
      )
    assertThat(archiveMutant).isNotEqualTo(source)
    assertWithMessage("archive original")
      .that(runArchiveSemanticSeparation(source, "original").exitCode)
      .isEqualTo(0)
    assertWithMessage("archive semantic mutant")
      .that(runArchiveSemanticSeparation(archiveMutant, "semantic-mutant").exitCode)
      .isNotEqualTo(0)

    val persistCall = "validate_root_checksum_inventory \"${'$'}evidence_dir\" || return 70"
    val persistMutant =
      source.replace(
        persistCall,
        "$persistCall\n  " +
          "validate_archive_semantics \"${'$'}evidence_dir\" " +
          "\"${'$'}CS2A_IMPLEMENTATION_SHA\" /bin/false || return 70",
      )
    assertThat(persistMutant).isNotEqualTo(source)
    assertWithMessage("persist original")
      .that(runPersistSemanticSeparation(source, "original").exitCode)
      .isEqualTo(0)
    assertWithMessage("persist semantic mutant")
      .that(runPersistSemanticSeparation(persistMutant, "semantic-mutant").exitCode)
      .isNotEqualTo(0)
  }

  @Test
  fun `archive-only authenticates source assets without building the local driver`() {
    val workspace = Files.createDirectories(temporaryDirectory.resolve("archive-source-only"))
    write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
    val harness =
      """
      source ${quote(operator)}
      prepare_operator_source() { : >"${'$'}PWD/build/source-authenticated"; }
      prepare_local_driver() { : >"${'$'}PWD/build/driver-built"; return 97; }
      verify_remote_bundle() { return 0; }
      validate_remote_final_handoff() { return 0; }
      archive_remote_attempt() { : >"${'$'}PWD/build/archive-called"; return 70; }
      ssh() { return 97; }
      rsync() { return 97; }
      if operator_main --archive-only \
        /opt/revoman-benchmark/runs/cs2a.Source123 \
        /run/revoman-cs2a/governor-state.Source123; then
        exit 98
      else
        test "${'$'}?" = 70
      fi
      test -f "${'$'}PWD/build/source-authenticated"
      test ! -e "${'$'}PWD/build/driver-built"
      test -f "${'$'}PWD/build/archive-called"
      """
        .trimIndent()

    assertProcessSucceeds(listOf("/bin/bash", "-c", harness), workspace)
  }

  @Test
  fun `selection rejects an implementation mismatch before source or driver preparation`() {
    val workspace = Files.createDirectories(temporaryDirectory.resolve("selection-source-binding"))
    write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
    val harness =
      """
      source ${quote(operator)}
      prepare_operator_source() { : >"${'$'}PWD/build/source-prepared"; return 97; }
      prepare_local_driver() { : >"${'$'}PWD/build/driver-built"; return 97; }
      if operator_main --validate-attempt /tmp/attempt ${"d".repeat(40)} ${"e".repeat(40)}; then
        exit 98
      fi
      test ! -e "${'$'}PWD/build/source-prepared"
      test ! -e "${'$'}PWD/build/driver-built"
      """
        .trimIndent()

    assertProcessSucceeds(listOf("/bin/bash", "-c", harness), workspace)
  }

  @Test
  fun `source authentication rejects a changed operator asset before remote install`() {
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("source-asset-binding")).toRealPath()
    val bundle = Files.createDirectories(workspace.resolve("docs/superpowers/benchmarks/operators"))
    listOf(operator, controlledRunner, supervisor, manifestValidator).forEach { source ->
      Files.copy(source, bundle.resolve(source.fileName), StandardCopyOption.REPLACE_EXISTING)
    }
    assertProcessSucceeds(listOf("git", "init", "-q"), workspace)
    assertProcessSucceeds(listOf("git", "config", "user.name", "CS2a Test"), workspace)
    assertProcessSucceeds(
      listOf("git", "config", "user.email", "cs2a-test@example.invalid"),
      workspace,
    )
    write(workspace.resolve(".gitignore"), "build/\n")
    assertProcessSucceeds(listOf("git", "add", "."), workspace)
    assertProcessSucceeds(listOf("git", "commit", "-qm", "authenticated source"), workspace)
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    write(workspace.resolve("build/cs2a-implementation-sha"), "$implementation\n")
    val testOperator = bundle.resolve(operator.fileName)
    val dirtyRunner = bundle.resolve(controlledRunner.fileName)
    write(dirtyRunner, Files.readString(dirtyRunner) + "# unauthenticated mutation\n")
    val remoteMarker = workspace.resolve("build/remote-install-called")
    val harness =
      """
      source "${'$'}1"
      REMOTE_MARKER=${'$'}2
      install_remote_bundle() { : >"${'$'}REMOTE_MARKER"; return 70; }
      if operator_main; then exit 98; else test "${'$'}?" = 70; fi
      test ! -e "${'$'}REMOTE_MARKER"
      """
        .trimIndent()
    assertProcessSucceeds(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "source-auth-harness",
        testOperator.toString(),
        remoteMarker.toString(),
      ),
      workspace,
    )
    val comparison =
      "    cmp -s \"${'$'}OPERATOR_DIR/${'$'}asset\" " +
        "\"${'$'}detached_operator_dir/${'$'}asset\" || return 1"
    val authenticatedSource = Files.readString(testOperator)
    assertThat(authenticatedSource).contains(comparison)
    write(testOperator, authenticatedSource.replace(comparison, "    : # comparison deleted"))
    Files.deleteIfExists(remoteMarker)
    assertThat(
        run(
            listOf(
              "/bin/bash",
              "-c",
              harness,
              "source-auth-harness",
              testOperator.toString(),
              remoteMarker.toString(),
            ),
            workspace,
          )
          .exitCode
      )
      .isNotEqualTo(0)
  }

  @Test
  fun `remote byte inventory authenticates every copied manifest result log and meta byte`() {
    val archive = createRemoteByteFixture("remote-bytes")
    assertBashFunctionSucceeds("validate_remote_byte_inventory ${quote(archive)}")

    write(archive.resolve("results/cold-aa.json"), "transfer mutation\n")
    assertBashFunctionFails(
      "validate_remote_byte_inventory ${quote(archive)}",
      "copied result byte mutation",
    )

    val extra = createRemoteByteFixture("remote-bytes-extra")
    write(extra.resolve("meta/uninventoried.txt"), "extra\n")
    assertBashFunctionFails(
      "validate_remote_byte_inventory ${quote(extra)}",
      "uninventoried copied metadata",
    )
  }

  @Test
  fun `safe partial evidence publishes atomically with failed semantic status`() {
    val implementation = IMPLEMENTATION_SHA
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("partial-workspace")).toRealPath()
    val parent =
      Files.createDirectories(
        workspace.resolve("docs/superpowers/benchmarks/results/v1/cs2a-$implementation")
      )
    val stage = Files.createDirectories(parent.resolve(".cs2a-archive-stage.partial"))
    write(stage.resolve("meta/stage.txt"), "setup\n")
    write(stage.resolve("meta/local-validation-passed.txt"), "false\n")
    write(stage.resolve("meta/operator-final-exit.txt"), "70\n")
    val canonical = parent.resolve("operator-failure.partial")
    val marker = Files.createDirectories(workspace.resolve("build")).resolve("marker.txt")

    assertProcessSucceeds(
      listOf(
        "/bin/bash",
        "-c",
        "source ${quote(operator)}; " +
          publicationToolPrelude +
          "; " +
          "publish_archive ${quote(stage)} ${quote(canonical)} ${quote(marker)} $implementation",
      ),
      workspace,
    )

    assertThat(Files.readString(canonical.resolve("meta/local-validation-passed.txt")).trim())
      .isEqualTo("false")
    assertThat(Files.readString(canonical.resolve("meta/operator-final-exit.txt")).trim())
      .isEqualTo("70")
    assertThat(Files.readString(marker).trim()).isEqualTo(canonical.toString())
  }

  @Test
  fun `root post status is persisted once as mode 0400`() {
    val remoteStatus = temporaryDirectory.resolve("remote-post-status.txt")
    Files.createDirectories(temporaryDirectory.resolve("build"))
    val command =
      """
      source ${quote(operator)}
      export FAKE_REMOTE_STATUS=${quote(remoteStatus)}
      scp() { return 0; }
      ssh() {
        case "${'$'}*" in
          *"dzdo cat"*) cat "${'$'}FAKE_REMOTE_STATUS" ;;
          *)
            if test ! -e "${'$'}FAKE_REMOTE_STATUS"; then
              (umask 077; set -o noclobber; printf '%s\n' 7 >"${'$'}FAKE_REMOTE_STATUS")
            fi
            case "${'$'}*" in *"chmod 0400"*) chmod 0400 "${'$'}FAKE_REMOTE_STATUS" ;; esac
            ;;
        esac
      }
      persist_original_post_status /run/revoman-cs2a/governor-state.Mock123 7
      """
        .trimIndent()

    assertProcessSucceeds(listOf("/bin/bash", "-c", command), temporaryDirectory)
    assertThat(Files.readString(remoteStatus)).isEqualTo("7\n")
    assertThat(Files.getPosixFilePermissions(remoteStatus))
      .containsExactly(PosixFilePermission.OWNER_READ)
  }

  @Test
  fun `root post status rejects an existing symlink without touching its victim`() {
    val state = Files.createDirectories(temporaryDirectory.resolve("governor-state.MockSymlink"))
    val victim = temporaryDirectory.resolve("root-owned-status-victim.txt")
    write(victim, "7\n")
    victim.toFile().setReadable(false, false)
    victim.toFile().setReadable(true, true)
    val destination = state.resolve("operator-post-supervisor-exit.txt")
    Files.createSymbolicLink(destination, victim)
    Files.createDirectories(temporaryDirectory.resolve("build"))
    val harness =
      """
      source ${quote(operator)}
      FAKE_STATE=${quote(state)}
      scp() { return 0; }
      ssh() {
        if test "${'$'}1" = -tt; then shift; fi
        shift
        command=${'$'}1
        command=${'$'}{command//\/run\/revoman-cs2a\/governor-state.MockSymlink/${'$'}FAKE_STATE}
        dzdo() {
          if test "${'$'}1" = stat; then printf '%s\n' 0:0:400; return 0; fi
          "${'$'}@"
        }
        eval "${'$'}command"
      }
      if persist_original_post_status \
        /run/revoman-cs2a/governor-state.MockSymlink 7; then
        exit 98
      fi
      test -L ${quote(destination)}
      test "${'$'}(cat ${quote(victim)})" = 7
      """
        .trimIndent()

    assertProcessSucceeds(listOf("/bin/bash", "-c", harness), temporaryDirectory)
    assertThat(Files.isSymbolicLink(destination)).isTrue()
    assertThat(Files.readString(victim)).isEqualTo("7\n")
  }

  @Test
  fun `safe partial attempt persists successfully while semantic selection still rejects it`() {
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("partial-persistence")).toRealPath()
    assertProcessSucceeds(listOf("git", "init", "-q"), workspace)
    assertProcessSucceeds(listOf("git", "config", "user.name", "CS2a Test"), workspace)
    assertProcessSucceeds(
      listOf("git", "config", "user.email", "cs2a-test@example.invalid"),
      workspace,
    )
    write(workspace.resolve(".gitignore"), "build/\n")
    assertProcessSucceeds(listOf("git", "add", ".gitignore"), workspace)
    assertProcessSucceeds(listOf("git", "commit", "-qm", "implementation"), workspace)
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    val attempt =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/operator-failure.fixture"
      )
    write(attempt.resolve("meta/stage.txt"), "setup\n")
    write(attempt.resolve("meta/local-validation-passed.txt"), "false\n")
    write(attempt.resolve("meta/operator-final-exit.txt"), "70\n")
    write(workspace.resolve("build/cs2a-operator-status.txt"), "70\n")
    write(workspace.resolve("build/cs2a-local-evidence-dir.txt"), "$attempt\n")
    val persist =
      """
      source ${quote(operator)}
      CS2A_IMPLEMENTATION_SHA=$implementation
      LOCAL_DRIVER=/bin/false
      write_root_checksum_inventory ${quote(attempt)}
      persist_attempt 70
      """
        .trimIndent()

    assertProcessSucceeds(listOf("/bin/bash", "-c", persist), workspace)
    val evidenceSha =
      Files.readString(workspace.resolve("build/cs2a-attempt-evidence-sha.txt")).trim()
    assertThat(
        run(listOf("git", "log", "-1", "--format=%H", "--", attempt.toString()), workspace)
          .output
          .trim()
      )
      .isEqualTo(evidenceSha)

    val secondAttempt = attempt.parent.resolve("operator-failure.second")
    write(secondAttempt.resolve("meta/stage.txt"), "setup\n")
    write(secondAttempt.resolve("meta/local-validation-passed.txt"), "false\n")
    write(secondAttempt.resolve("meta/operator-final-exit.txt"), "70\n")
    write(workspace.resolve("build/cs2a-local-evidence-dir.txt"), "$secondAttempt\n")
    val persistSecond =
      """
      source ${quote(operator)}
      CS2A_IMPLEMENTATION_SHA=$implementation
      LOCAL_DRIVER=/bin/false
      write_root_checksum_inventory ${quote(secondAttempt)}
      persist_attempt 70
      """
        .trimIndent()
    assertProcessSucceeds(listOf("/bin/bash", "-c", persistSecond), workspace)
    val secondEvidenceSha =
      Files.readString(workspace.resolve("build/cs2a-attempt-evidence-sha.txt")).trim()
    assertThat(secondEvidenceSha).isNotEqualTo(evidenceSha)

    val selection =
      """
      source ${quote(operator)}
      validate_persisted_attempt ${quote(attempt)} $implementation $evidenceSha /bin/false
      """
        .trimIndent()
    val rejected = run(listOf("/bin/bash", "-c", selection), workspace)
    assertWithMessage(rejected.output).that(rejected.exitCode).isNotEqualTo(0)
  }

  @Test
  fun `persist-only entrypoint commits the existing failure when source preparation is unavailable`() {
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("persist-before-preparation")).toRealPath()
    assertProcessSucceeds(listOf("git", "init", "-q"), workspace)
    assertProcessSucceeds(listOf("git", "config", "user.name", "CS2a Test"), workspace)
    assertProcessSucceeds(
      listOf("git", "config", "user.email", "cs2a-test@example.invalid"),
      workspace,
    )
    write(workspace.resolve(".gitignore"), "build/\n")
    assertProcessSucceeds(listOf("git", "add", ".gitignore"), workspace)
    assertProcessSucceeds(listOf("git", "commit", "-qm", "implementation"), workspace)
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    write(workspace.resolve("build/cs2a-implementation-sha"), "$implementation\n")
    val firstFailure =
      """
      source ${quote(operator)}
      $publicationToolPrelude
      prepare_operator_source() { return 88; }
      if operator_main; then exit 91; else test "${'$'}?" = 70; fi
      """
        .trimIndent()
    assertProcessSucceeds(listOf("/bin/bash", "-c", firstFailure), workspace)
    val attempt =
      Path.of(Files.readString(workspace.resolve("build/cs2a-local-evidence-dir.txt")).trim())
    assertThat(attempt.fileName.toString()).startsWith("operator-failure.")
    write(workspace.resolve("build/cs2a-operator-status.txt"), "70\n")
    val persist =
      """
      source ${quote(operator)}
      prepare_operator_source() {
        : >"${'$'}PWD/build/persist-preparation-was-called"
        return 88
      }
      operator_main --persist-only 70
      test ! -e "${'$'}PWD/build/persist-preparation-was-called"
      """
        .trimIndent()

    assertProcessSucceeds(listOf("/bin/bash", "-c", persist), workspace)
    val evidenceSha =
      Files.readString(workspace.resolve("build/cs2a-attempt-evidence-sha.txt")).trim()
    assertThat(
        run(listOf("git", "log", "-1", "--format=%H", "--", attempt.toString()), workspace)
          .output
          .trim()
      )
      .isEqualTo(evidenceSha)
  }

  @Test
  fun `persist-only rejects unsafe or invalid inventory before staging an attempt`() {
    listOf("symlink", "duplicate-inventory", "unsafe-path").forEach { mutation ->
      val workspace = createPersistenceWorkspace("precommit-$mutation")
      val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
      val initialHead = implementation
      val attempt =
        workspace.resolve(
          "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/operator-failure.$mutation"
        )
      write(attempt.resolve("meta/operator-final-exit.txt"), "70\n")
      write(workspace.resolve("build/cs2a-implementation-sha"), "$implementation\n")
      write(workspace.resolve("build/cs2a-operator-status.txt"), "70\n")
      write(workspace.resolve("build/cs2a-local-evidence-dir.txt"), "$attempt\n")
      assertProcessSucceeds(
        listOf(
          "/bin/bash",
          "-c",
          "source ${quote(operator)}; write_root_checksum_inventory ${quote(attempt)}",
        ),
        workspace,
      )
      when (mutation) {
        "symlink" ->
          Files.createSymbolicLink(
            attempt.resolve("meta/unsafe-link"),
            attempt.resolve("meta/operator-final-exit.txt"),
          )
        "duplicate-inventory" -> {
          val inventory = attempt.resolve("evidence-sha256sums.txt")
          Files.writeString(
            inventory,
            Files.readString(inventory) + Files.readAllLines(inventory)[0] + "\n",
          )
        }
        "unsafe-path" -> {
          val inventory = attempt.resolve("evidence-sha256sums.txt")
          Files.writeString(
            inventory,
            Files.readString(inventory) + "${"a".repeat(64)}  ./../escape\n",
          )
        }
      }

      val result =
        run(
          listOf("/bin/bash", operator.toString(), "--persist-only", "70"),
          workspace,
        )

      assertWithMessage("$mutation\n${result.output}").that(result.exitCode).isNotEqualTo(0)
      assertThat(run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim())
        .isEqualTo(initialHead)
      assertThat(run(listOf("git", "ls-files", "--", attempt.toString()), workspace).output)
        .isEmpty()
      assertThat(Files.exists(workspace.resolve("build/cs2a-attempt-evidence-sha.txt"))).isFalse()
    }
  }

  @Test
  fun `persist-only force-adds ignored logs and committed tree exactly validates in a fresh checkout`() {
    val workspace = createPersistenceWorkspace("ignored-logs", extraIgnore = "logs\n")
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    val attempt =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/operator-failure.ignored-logs"
      )
    write(attempt.resolve("meta/operator-final-exit.txt"), "70\n")
    write(attempt.resolve("logs/ignored.stdout"), "authenticated ignored log\n")
    write(workspace.resolve("build/cs2a-implementation-sha"), "$implementation\n")
    write(workspace.resolve("build/cs2a-operator-status.txt"), "70\n")
    write(workspace.resolve("build/cs2a-local-evidence-dir.txt"), "$attempt\n")
    assertProcessSucceeds(
      listOf(
        "/bin/bash",
        "-c",
        "source ${quote(operator)}; write_root_checksum_inventory ${quote(attempt)}",
      ),
      workspace,
    )

    assertProcessSucceeds(
      listOf("/bin/bash", operator.toString(), "--persist-only", "70"),
      workspace,
    )
    val evidenceSha =
      Files.readString(workspace.resolve("build/cs2a-attempt-evidence-sha.txt")).trim()
    val relativeAttempt = workspace.relativize(attempt).toString()
    val treeNames =
      run(
          listOf("git", "ls-tree", "-r", "--name-only", evidenceSha, "--", relativeAttempt),
          workspace,
        )
        .output
    assertThat(treeNames).contains("$relativeAttempt/logs/ignored.stdout")

    val fresh = temporaryDirectory.resolve("ignored-logs-fresh")
    assertProcessSucceeds(listOf("git", "clone", "-q", workspace.toString(), fresh.toString()))
    assertProcessSucceeds(listOf("git", "checkout", "-q", evidenceSha), fresh)
    val freshRoot = fresh.toRealPath()
    val freshAttempt = freshRoot.resolve(relativeAttempt)
    val freshValidation =
      "source ${quote(operator)}; " +
        "validate_persisted_publication ${quote(freshAttempt)} $implementation $evidenceSha"
    assertProcessSucceeds(listOf("/bin/bash", "-c", freshValidation), freshRoot)
  }

  @Test
  fun `persist-only rejects transformed CRLF bytes before committing`() {
    val workspace = createPersistenceWorkspace("crlf-index-transform")
    assertProcessSucceeds(listOf("git", "config", "core.autocrlf", "input"), workspace)
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    val attempt =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/operator-failure.crlf"
      )
    write(attempt.resolve("meta/operator-final-exit.txt"), "70\n")
    write(attempt.resolve("logs/authenticated.log"), "first\r\nsecond\r\n")

    assertPersistOnlyFailsBeforeCommit(workspace, attempt, implementation)
    assertThat(Files.readAllBytes(attempt.resolve("logs/authenticated.log")))
      .isEqualTo("first\r\nsecond\r\n".toByteArray())
  }

  @Test
  fun `persist-only rejects a nested repository gitlink before committing`() {
    val workspace = createPersistenceWorkspace("nested-gitlink")
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    val attempt =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/operator-failure.gitlink"
      )
    write(attempt.resolve("meta/operator-final-exit.txt"), "70\n")
    val nested = Files.createDirectories(attempt.resolve("logs/nested-runtime"))
    assertProcessSucceeds(listOf("git", "init", "-q"), nested)
    assertProcessSucceeds(listOf("git", "config", "user.name", "Nested Test"), nested)
    assertProcessSucceeds(
      listOf("git", "config", "user.email", "nested@example.invalid"),
      nested,
    )
    write(nested.resolve("authenticated.log"), "nested authenticated bytes\n")
    assertProcessSucceeds(listOf("git", "add", "authenticated.log"), nested)
    assertProcessSucceeds(listOf("git", "commit", "-qm", "nested"), nested)

    assertPersistOnlyFailsBeforeCommit(workspace, attempt, implementation)
  }

  @Test
  fun `persist-only commit plumbing never executes porcelain commit hooks`() {
    val workspace = createPersistenceWorkspace("hook-free-persistence")
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    val attempt =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/operator-failure.hooks"
      )
    val evidence = attempt.resolve("meta/operator-final-exit.txt")
    write(evidence, "70\n")
    preparePersistOnly(workspace, attempt, implementation)
    val hooks = Files.createDirectories(workspace.resolve(".git/hooks"))
    val preCommit = hooks.resolve("pre-commit")
    write(
      preCommit,
      """
      #!/bin/sh
      printf '%s\n' invoked >${quote(workspace.resolve("build/pre-commit-invoked"))}
      printf '%s\n' substituted >${quote(evidence)}
      git add -f -- ${quote(evidence)}
      """
        .trimIndent() + "\n",
    )
    preCommit.toFile().setExecutable(true, false)
    val prepareCommitMessage = hooks.resolve("prepare-commit-msg")
    write(
      prepareCommitMessage,
      """
      #!/bin/sh
      printf '%s\n' invoked >${quote(workspace.resolve("build/prepare-message-invoked"))}
      printf '%s\n' outside >${quote(workspace.resolve("outside-staging.txt"))}
      git add -- ${quote(workspace.resolve("outside-staging.txt"))}
      """
        .trimIndent() + "\n",
    )
    prepareCommitMessage.toFile().setExecutable(true, false)

    val result = run(listOf("/bin/bash", operator.toString(), "--persist-only", "70"), workspace)

    assertWithMessage(result.output).that(result.exitCode).isEqualTo(0)
    assertThat(Files.exists(workspace.resolve("build/pre-commit-invoked"))).isFalse()
    assertThat(Files.exists(workspace.resolve("build/prepare-message-invoked"))).isFalse()
    assertThat(Files.readString(evidence)).isEqualTo("70\n")
    assertThat(run(listOf("git", "ls-files", "--", "outside-staging.txt"), workspace).output)
      .isEmpty()
  }

  @Test
  fun `persist-only disables index and reference transaction hooks`() {
    val workspace = createPersistenceWorkspace("plumbing-hook-free-persistence")
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    val attempt =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/operator-failure.plumbing-hooks"
      )
    val evidence = attempt.resolve("meta/operator-final-exit.txt")
    write(evidence, "70\n")
    preparePersistOnly(workspace, attempt, implementation)
    val hooks = Files.createDirectories(workspace.resolve(".git/hooks"))
    val hookMarker = workspace.resolve("build/plumbing-hook-invoked")
    val outside = workspace.resolve("outside-hook-staging.txt")
    listOf("post-index-change", "reference-transaction").forEach { name ->
      val hook = hooks.resolve(name)
      write(
        hook,
        """
        #!/bin/sh
        if test ! -e ${quote(hookMarker)}; then
          printf '%s\n' $name >${quote(hookMarker)}
          printf '%s\n' substituted >${quote(evidence)}
          printf '%s\n' outside >${quote(outside)}
        fi
        """
          .trimIndent() + "\n",
      )
      hook.toFile().setExecutable(true, false)
    }

    val result = run(listOf("/bin/bash", operator.toString(), "--persist-only", "70"), workspace)

    assertWithMessage(result.output).that(result.exitCode).isEqualTo(0)
    assertThat(Files.exists(hookMarker)).isFalse()
    assertThat(Files.exists(outside)).isFalse()
    assertThat(Files.readString(evidence)).isEqualTo("70\n")
  }

  @Test
  fun `persist-only rejects a frozen tree containing a concurrent outside index change`() {
    val workspace = createPersistenceWorkspace("outside-index-race")
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    val attempt =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/operator-failure.outside-race"
      )
    write(attempt.resolve("meta/operator-final-exit.txt"), "70\n")
    preparePersistOnly(workspace, attempt, implementation)
    val fakeBin = Files.createDirectories(temporaryDirectory.resolve("outside-index-fake-bin"))
    val realGit = run(listOf("/usr/bin/env", "sh", "-c", "command -v git"), workspace).output.trim()
    val outside = workspace.resolve("concurrent-outside.txt")
    val gitWrapper = fakeBin.resolve("git")
    write(
      gitWrapper,
      """
      #!/bin/sh
      command=
      for argument do
        case "${'$'}argument" in
          -c|core.hooksPath=/dev/null|--literal-pathspecs) ;;
          *) command=${'$'}argument; break ;;
        esac
      done
      if test "${'$'}command" = write-tree && test ! -e ${quote(outside)}; then
        printf '%s\n' outside >${quote(outside)}
        ${quote(Path.of(realGit))} add -- ${quote(outside)}
      fi
      exec ${quote(Path.of(realGit))} "${'$'}@"
      """
        .trimIndent() + "\n",
    )
    gitWrapper.toFile().setExecutable(true, false)

    val result =
      run(
        listOf(
          "/bin/bash",
          "-c",
          "PATH=${quote(fakeBin)}:\"${'$'}PATH\" /bin/bash ${quote(operator)} --persist-only 70",
        ),
        workspace,
      )

    assertWithMessage(result.output).that(result.exitCode).isNotEqualTo(0)
    assertThat(run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim())
      .isEqualTo(implementation)
    assertThat(run(listOf("git", "diff", "--cached", "--name-only"), workspace).output.trim())
      .isEqualTo("concurrent-outside.txt")
    assertThat(Files.exists(workspace.resolve("build/cs2a-attempt-evidence-sha.txt"))).isFalse()
  }

  @Test
  fun `persisted attempt history rejects an evidence merge commit`() {
    val workspace = createPersistenceWorkspace("merge-evidence-history")
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    val attempt =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/operator-failure.merge"
      )
    write(attempt.resolve("meta/operator-final-exit.txt"), "70\n")
    assertProcessSucceeds(
      listOf(
        "/bin/bash",
        "-c",
        "source ${quote(operator)}; write_root_checksum_inventory ${quote(attempt)}",
      ),
      workspace,
    )
    assertProcessSucceeds(listOf("git", "add", "-f", "--", attempt.toString()), workspace)
    val attemptTree = run(listOf("git", "write-tree"), workspace).output.trim()
    val implementationTree =
      run(listOf("git", "rev-parse", "$implementation^{tree}"), workspace).output.trim()
    val secondParent =
      run(
          listOf(
            "/bin/bash",
            "-c",
            "printf '%s\\n' side | git commit-tree $implementationTree -p $implementation",
          ),
          workspace,
        )
        .output
        .trim()
    val mergeEvidence =
      run(
          listOf(
            "/bin/bash",
            "-c",
            "printf '%s\\n' merge | git commit-tree $attemptTree " +
              "-p $implementation -p $secondParent",
          ),
          workspace,
        )
        .output
        .trim()
    assertProcessSucceeds(
      listOf("git", "update-ref", "HEAD", mergeEvidence, implementation),
      workspace,
    )
    val invocation = "validate_attempt_history ${quote(attempt)} $implementation $mergeEvidence"

    val rejected =
      run(
        listOf("/bin/bash", "-c", "source ${quote(operator)}; $invocation"),
        workspace,
      )

    assertWithMessage(rejected.output).that(rejected.exitCode).isNotEqualTo(0)
    assertThat(
        run(listOf("git", "rev-list", "--parents", "-n", "1", mergeEvidence), workspace)
          .output
          .trim()
          .split(' ')
      )
      .hasSize(3)
  }

  @Test
  fun `persist-only rejects an outside index change after freezing the evidence tree`() {
    val workspace = createPersistenceWorkspace("post-snapshot-index-race")
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    val attempt =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/" +
          "operator-failure.post-snapshot"
      )
    write(attempt.resolve("meta/operator-final-exit.txt"), "70\n")
    preparePersistOnly(workspace, attempt, implementation)
    val fakeBin = Files.createDirectories(temporaryDirectory.resolve("post-snapshot-fake-bin"))
    val realGit = run(listOf("/usr/bin/env", "sh", "-c", "command -v git"), workspace).output.trim()
    val outside = workspace.resolve("post-snapshot-outside.txt")
    val count = workspace.resolve("build/write-tree-count")
    val gitWrapper = fakeBin.resolve("git")
    write(
      gitWrapper,
      """
      #!/bin/sh
      command=
      for argument do
        case "${'$'}argument" in
          -c|core.hooksPath=/dev/null|--literal-pathspecs) ;;
          *) command=${'$'}argument; break ;;
        esac
      done
      if test "${'$'}command" = write-tree; then
        current=0
        if test -f ${quote(count)}; then current=${'$'}(cat ${quote(count)}); fi
        current=${'$'}((current + 1))
        printf '%s\n' "${'$'}current" >${quote(count)}
        if test "${'$'}current" = 2; then
          printf '%s\n' outside >${quote(outside)}
          ${quote(Path.of(realGit))} -c core.hooksPath=/dev/null add -- ${quote(outside)}
        fi
      fi
      exec ${quote(Path.of(realGit))} "${'$'}@"
      """
        .trimIndent() + "\n",
    )
    gitWrapper.toFile().setExecutable(true, false)

    val result =
      run(
        listOf(
          "/bin/bash",
          "-c",
          "PATH=${quote(fakeBin)}:\"${'$'}PATH\" /bin/bash ${quote(operator)} --persist-only 70",
        ),
        workspace,
      )

    assertWithMessage(result.output).that(result.exitCode).isNotEqualTo(0)
    assertThat(run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim())
      .isEqualTo(implementation)
    assertThat(run(listOf("git", "diff", "--cached", "--name-only"), workspace).output.trim())
      .isEqualTo("post-snapshot-outside.txt")
    assertThat(Files.readString(count).trim().toInt()).isAtLeast(2)
    assertThat(Files.exists(workspace.resolve("build/cs2a-attempt-evidence-sha.txt"))).isFalse()
  }

  @Test
  fun `persist-only CAS rejection leaves HEAD and authority marker unchanged`() {
    val workspace = createPersistenceWorkspace("cas-rejection")
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    val attempt =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/operator-failure.cas"
      )
    write(attempt.resolve("meta/operator-final-exit.txt"), "70\n")
    preparePersistOnly(workspace, attempt, implementation)
    val fakeBin = Files.createDirectories(temporaryDirectory.resolve("cas-fake-bin"))
    val realGit = run(listOf("/usr/bin/env", "sh", "-c", "command -v git"), workspace).output.trim()
    val gitWrapper = fakeBin.resolve("git")
    write(
      gitWrapper,
      """
      #!/bin/sh
      for argument do
        if test "${'$'}argument" = update-ref; then exit 73; fi
      done
      exec ${quote(Path.of(realGit))} "${'$'}@"
      """
        .trimIndent() + "\n",
    )
    gitWrapper.toFile().setExecutable(true, false)
    val command =
      "PATH=${quote(fakeBin)}:\"${'$'}PATH\" /bin/bash ${quote(operator)} --persist-only 70"

    val result = run(listOf("/bin/bash", "-c", command), workspace)

    assertWithMessage(result.output).that(result.exitCode).isNotEqualTo(0)
    assertThat(run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim())
      .isEqualTo(implementation)
    assertThat(run(listOf("git", "diff", "--cached", "--name-only"), workspace).output).isEmpty()
    assertThat(Files.exists(workspace.resolve("build/cs2a-attempt-evidence-sha.txt"))).isFalse()
  }

  @Test
  fun `persist-only rejects wildcard attempt basename before any Git pathspec can touch a sibling`() {
    val workspace = createPersistenceWorkspace("literal-attempt-pathspec")
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    val parent = workspace.resolve("docs/superpowers/benchmarks/results/v1/cs2a-$implementation")
    val wildcardAttempt = parent.resolve("operator-failure.*")
    val sibling = parent.resolve("operator-failure.sibling/meta/sibling.txt")
    write(wildcardAttempt.resolve("meta/operator-final-exit.txt"), "70\n")
    write(sibling, "must remain untracked\n")
    preparePersistOnly(workspace, wildcardAttempt, implementation)
    val fakeBin = Files.createDirectories(temporaryDirectory.resolve("literal-fake-bin"))
    val trace = workspace.resolve("build/git-pathspec-trace")
    val realGit = run(listOf("/usr/bin/env", "sh", "-c", "command -v git"), workspace).output.trim()
    val gitWrapper = fakeBin.resolve("git")
    write(
      gitWrapper,
      """
      #!/bin/sh
      printf '%s\n' "${'$'}*" >>${quote(trace)}
      exec ${quote(Path.of(realGit))} "${'$'}@"
      """
        .trimIndent() + "\n",
    )
    gitWrapper.toFile().setExecutable(true, false)

    val result =
      run(
        listOf(
          "/bin/bash",
          "-c",
          "PATH=${quote(fakeBin)}:\"${'$'}PATH\" /bin/bash ${quote(operator)} --persist-only 70",
        ),
        workspace,
      )

    assertWithMessage(result.output).that(result.exitCode).isNotEqualTo(0)
    assertThat(Files.exists(trace)).isFalse()
    assertThat(run(listOf("git", "ls-files", "--", sibling.toString()), workspace).output).isEmpty()
    assertThat(run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim())
      .isEqualTo(implementation)
  }

  @Test
  fun `persisted publication rejects mutable bytes even with a regenerated checksum`() {
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("mutable-evidence")).toRealPath()
    assertProcessSucceeds(listOf("git", "init", "-q"), workspace)
    assertProcessSucceeds(listOf("git", "config", "user.name", "CS2a Test"), workspace)
    assertProcessSucceeds(
      listOf("git", "config", "user.email", "cs2a-test@example.invalid"),
      workspace,
    )
    write(workspace.resolve(".gitignore"), "build/\n")
    assertProcessSucceeds(listOf("git", "add", ".gitignore"), workspace)
    assertProcessSucceeds(listOf("git", "commit", "-qm", "implementation"), workspace)
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    val attempt =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/operator-failure.mutable"
      )
    write(attempt.resolve("meta/stage.txt"), "setup\n")
    write(attempt.resolve("meta/local-validation-passed.txt"), "false\n")
    write(attempt.resolve("meta/operator-final-exit.txt"), "70\n")
    val persist =
      """
      source ${quote(operator)}
      CS2A_IMPLEMENTATION_SHA=$implementation
      write_root_checksum_inventory ${quote(attempt)}
      git add -- ${quote(attempt)}
      git commit -qm evidence
      evidence_sha=${'$'}(git rev-parse HEAD)
      validate_persisted_publication ${quote(attempt)} $implementation "${'$'}evidence_sha"
      mkdir -p build
      printf '%s\n' "${'$'}evidence_sha" >build/evidence-sha
      """
        .trimIndent()
    assertProcessSucceeds(listOf("/bin/bash", "-c", persist), workspace)
    val evidenceSha = Files.readString(workspace.resolve("build/evidence-sha")).trim()

    write(attempt.resolve("meta/operator-final-exit.txt"), "71\n")
    val mutation =
      """
      source ${quote(operator)}
      write_root_checksum_inventory ${quote(attempt)}
      validate_persisted_publication ${quote(attempt)} $implementation $evidenceSha
      """
        .trimIndent()
    val result = run(listOf("/bin/bash", "-c", mutation), workspace)
    assertWithMessage(result.output).that(result.exitCode).isNotEqualTo(0)
  }

  @Test
  fun `pre-marker operator failure publishes only authentic local evidence and remains persistable`() {
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("local-operator-failure")).toRealPath()
    assertProcessSucceeds(listOf("git", "init", "-q"), workspace)
    assertProcessSucceeds(listOf("git", "config", "user.name", "CS2a Test"), workspace)
    assertProcessSucceeds(
      listOf("git", "config", "user.email", "cs2a-test@example.invalid"),
      workspace,
    )
    write(workspace.resolve(".gitignore"), "build/\n")
    assertProcessSucceeds(listOf("git", "add", ".gitignore"), workspace)
    assertProcessSucceeds(listOf("git", "commit", "-qm", "implementation"), workspace)
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    write(workspace.resolve("build/cs2a-local-validation-driver.log"), "local build failed\n")
    write(workspace.resolve("build/cs2a-supervisor.log"), "no authenticated marker\n")
    write(workspace.resolve("build/cs2a-supervisor-exit.txt"), "70\n")
    val publish =
      """
      source ${quote(operator)}
      $publicationToolPrelude
      CS2A_IMPLEMENTATION_SHA=$implementation
      publish_local_operator_failure supervisor 70
      """
        .trimIndent()

    assertProcessSucceeds(listOf("/bin/bash", "-c", publish), workspace)
    val attempt =
      Path.of(Files.readString(workspace.resolve("build/cs2a-local-evidence-dir.txt")).trim())
    assertThat(attempt.fileName.toString()).startsWith("operator-failure.")
    assertThat(Files.readString(attempt.resolve("meta/operator-failure-phase.txt")))
      .isEqualTo("supervisor\n")
    assertThat(Files.readString(attempt.resolve("meta/operator-final-exit.txt"))).isEqualTo("70\n")
    assertThat(Files.readString(attempt.resolve("meta/local-validation-passed.txt")))
      .isEqualTo("false\n")
    assertThat(Files.readString(attempt.resolve("meta/remote-evidence-present.txt")))
      .isEqualTo("false\n")
    assertThat(Files.readString(attempt.resolve("meta/operator-supervisor.log")))
      .isEqualTo("no authenticated marker\n")
    assertThat(Files.exists(attempt.resolve("meta/remote-byte-sha256sums.txt"))).isFalse()
    assertThat(Files.exists(attempt.resolve("manifests"))).isFalse()
    assertThat(Files.exists(attempt.resolve("results"))).isFalse()
    assertThat(Files.exists(attempt.resolve("logs"))).isFalse()

    write(workspace.resolve("build/cs2a-operator-status.txt"), "70\n")
    val persist =
      """
      source ${quote(operator)}
      CS2A_IMPLEMENTATION_SHA=$implementation
      persist_attempt 70
      """
        .trimIndent()
    assertProcessSucceeds(listOf("/bin/bash", "-c", persist), workspace)
  }

  @Test
  fun `operator main safely publishes install and pre-marker supervisor failures`() {
    listOf("install", "markers").forEach { expectedPhase ->
      val workspace =
        Files.createDirectories(temporaryDirectory.resolve("main-$expectedPhase")).toRealPath()
      write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
      write(workspace.resolve("build/cs2a-local-validation-driver.log"), "local preparation\n")
      val failureOverrides =
        if (expectedPhase == "install") {
          """
          install_remote_bundle() { return 1; }
          run_remote_supervisor() { return 97; }
          persist_original_post_status() { return 97; }
          archive_remote_attempt() { return 97; }
          """
            .trimIndent()
        } else {
          """
          install_remote_bundle() { return 0; }
          run_remote_supervisor() {
            printf '%s\n' 'supervisor failed before markers' >"${'$'}PWD/build/cs2a-supervisor.log"
            printf '%s\n' 70 >"${'$'}PWD/build/cs2a-supervisor-exit.txt"
            return 70
          }
          persist_original_post_status() { return 97; }
          archive_remote_attempt() { return 97; }
          """
            .trimIndent()
        }
      val command =
        """
        source ${quote(operator)}
        $publicationToolPrelude
        prepare_operator_source() { AUTHENTICATED_SOURCE_ROOT=/tmp; }
        $failureOverrides
        if operator_main; then status=0; else status=${'$'}?; fi
        test "${'$'}status" = 70
        """
          .trimIndent()

      assertProcessSucceeds(listOf("/bin/bash", "-c", command), workspace)
      val attempt =
        Path.of(Files.readString(workspace.resolve("build/cs2a-local-evidence-dir.txt")).trim())
      assertThat(Files.readString(attempt.resolve("meta/operator-failure-phase.txt")))
        .isEqualTo("$expectedPhase\n")
      assertThat(Files.readString(attempt.resolve("meta/local-validation-passed.txt")))
        .isEqualTo("false\n")
      assertThat(Files.readString(attempt.resolve("meta/operator-final-exit.txt")))
        .isEqualTo("70\n")
    }
  }

  @Test
  fun `operator main production call sites execute post-status persistence and archiving`() {
    val source = Files.readString(operator)
    val persistInvocation =
      "if ! persist_original_post_status \"${'$'}resume_state\" \"${'$'}status\"; then"
    val archiveInvocation = "archive_remote_attempt \"${'$'}resume_run\" \"${'$'}resume_state\""
    assertThat(source).contains(persistInvocation)
    assertThat(source).contains(archiveInvocation)
    val original = temporaryDirectory.resolve("operator-main-original.sh")
    val missingPersist = temporaryDirectory.resolve("operator-main-missing-persist.sh")
    val missingArchive = temporaryDirectory.resolve("operator-main-missing-archive.sh")
    write(original, source)
    write(missingPersist, source.replace(persistInvocation, "if ! :; then"))
    write(missingArchive, source.replace(archiveInvocation, ":"))

    val originalResult = runOperatorMainCallSiteHarness(original, "original")
    assertWithMessage(originalResult.output).that(originalResult.exitCode).isEqualTo(0)
    assertThat(runOperatorMainCallSiteHarness(missingPersist, "missing-persist").exitCode)
      .isNotEqualTo(0)
    assertThat(runOperatorMainCallSiteHarness(missingArchive, "missing-archive").exitCode)
      .isNotEqualTo(0)
  }

  @Test
  fun `operator public modes execute exact dispatch call sites and deletion mutants fail`() {
    val source = Files.readString(operator)
    val callSites =
      linkedMapOf(
        "persist" to "persist_attempt \"${'$'}2\"",
        "validate" to
          "validate_persisted_attempt \"${'$'}attempt\" \"${'$'}implementation\" " +
            "\"${'$'}evidence_sha\" \"${'$'}LOCAL_DRIVER\"",
        "archive" to "archive_remote_attempt \"${'$'}resume_run\" \"${'$'}resume_state\"",
      )
    callSites.forEach { (mode, callSite) ->
      assertThat(source).contains(callSite)
      val original = temporaryDirectory.resolve("operator-mode-$mode-original.sh")
      val mutant = temporaryDirectory.resolve("operator-mode-$mode-mutant.sh")
      write(original, source)
      write(mutant, source.replace(callSite, ":"))
      val originalResult = runOperatorModeHarness(original, mode)
      assertWithMessage("$mode original\n${originalResult.output}")
        .that(originalResult.exitCode)
        .isEqualTo(0)
      val mutantResult = runOperatorModeHarness(mutant, mode)
      assertWithMessage("$mode deletion mutant\n${mutantResult.output}")
        .that(mutantResult.exitCode)
        .isNotEqualTo(0)
    }
  }

  @Test
  fun `remote refresh delegates final handoff to installed reviewed supervisor CLI`() {
    val source = Files.readString(operator)
    val invocation =
      "dzdo /opt/revoman-benchmark/cs2a-governor-supervisor.sh \\\n" +
        "       --publish-final-handoff '${'$'}run_root' '${'$'}governor_state'"
    assertThat(source).contains(invocation)
    val mutant = source.replace(invocation, ":")
    assertThat(mutant).isNotEqualTo(source)
    val original = temporaryDirectory.resolve("refresh-original.sh")
    val missingCall = temporaryDirectory.resolve("refresh-missing-call.sh")
    write(original, source)
    write(missingCall, mutant)

    assertRefreshCallSite(original, expectedSuccess = true)
    assertRefreshCallSite(missingCall, expectedSuccess = false)
  }

  @Test
  fun `final handoff failure publishes its actual exit as local-only evidence`() {
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("final-handoff-failure")).toRealPath()
    write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
    val harness =
      """
      source ${quote(operator)}
      $publicationToolPrelude
      prepare_operator_source() { AUTHENTICATED_SOURCE_ROOT=/tmp; }
      install_remote_bundle() { return 0; }
      run_remote_supervisor() {
        printf '%s\n' \
          'RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.FinalHandoff123' \
          'GOVERNOR_STATE=/run/revoman-cs2a/governor-state.FinalHandoff123' \
          >"${'$'}PWD/build/cs2a-supervisor.log"
        printf '%s\n' 23 >"${'$'}PWD/build/cs2a-supervisor-exit.txt"
        return 23
      }
      persist_original_post_status() { return 0; }
      refresh_remote_final_handoff() { return 41; }
      archive_remote_attempt() { return 99; }
      if operator_main; then exit 98; else test "${'$'}?" = 70; fi
      """
        .trimIndent()

    assertProcessSucceeds(listOf("/bin/bash", "-c", harness), workspace)
    val attempt =
      Path.of(Files.readString(workspace.resolve("build/cs2a-local-evidence-dir.txt")).trim())
    assertThat(Files.readString(attempt.resolve("meta/operator-failure-phase.txt")))
      .isEqualTo("final-handoff\n")
    assertThat(Files.readString(attempt.resolve("meta/operator-failure-source-exit.txt")))
      .isEqualTo("41\n")
    assertThat(Files.readString(attempt.resolve("meta/remote-evidence-present.txt")))
      .isEqualTo("false\n")
    assertThat(Files.exists(attempt.resolve("manifests"))).isFalse()
    assertThat(Files.exists(workspace.resolve("build/archive-called"))).isFalse()
  }

  @Test
  fun `final handoff failure reports loss when local evidence publication also fails`() {
    val workspace = Files.createDirectories(temporaryDirectory.resolve("final-handoff-loss"))
    write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
    val harness =
      """
      source ${quote(operator)}
      prepare_operator_source() { AUTHENTICATED_SOURCE_ROOT=/tmp; }
      install_remote_bundle() { return 0; }
      run_remote_supervisor() {
        printf '%s\n' \
          'RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.FinalHandoffLoss123' \
          'GOVERNOR_STATE=/run/revoman-cs2a/governor-state.FinalHandoffLoss123' \
          >"${'$'}PWD/build/cs2a-supervisor.log"
        printf '%s\n' 0 >"${'$'}PWD/build/cs2a-supervisor-exit.txt"
      }
      persist_original_post_status() { return 0; }
      refresh_remote_final_handoff() { return 41; }
      publish_local_operator_failure() { return 42; }
      operator_main
      """
        .trimIndent()

    val result = run(listOf("/bin/bash", "-c", harness), workspace)

    assertThat(result.exitCode).isEqualTo(70)
    assertThat(result.output).contains("unable to preserve final-handoff failure")
  }

  @Test
  fun `archive only preserves validation failure locally without copying remote bytes`() {
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("archive-validation-failure")).toRealPath()
    write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
    write(workspace.resolve("build/cs2a-supervisor.log"), "trusted supervisor log\n")
    write(workspace.resolve("build/cs2a-supervisor-exit.txt"), "70\n")
    val harness =
      """
      source ${quote(operator)}
      $publicationToolPrelude
      RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.Existing123
      GOVERNOR_STATE=/run/revoman-cs2a/governor-state.Existing123
      prepare_operator_source() { AUTHENTICATED_SOURCE_ROOT=/tmp; }
      prepare_local_driver() { : >"${'$'}PWD/build/driver-called"; return 97; }
      install_remote_bundle() { : >"${'$'}PWD/build/install-called"; return 97; }
      run_remote_supervisor() { : >"${'$'}PWD/build/runner-called"; return 97; }
      verify_remote_bundle() { return 0; }
      refresh_remote_final_handoff() { : >"${'$'}PWD/build/refresh-called"; }
      ssh() {
        case "${'$'}*" in
          *--publish-final-handoff* | *cs2a-controlled-run.sh*)
            : >"${'$'}PWD/build/forbidden-remote-mode"
            return 97
            ;;
          *--validate-final-handoff*)
            test "${'$'}#" = 3
            test "${'$'}1" = -tt
            test "${'$'}2" = "${'$'}REMOTE_HOST"
            test "${'$'}3" = \
              "dzdo /opt/revoman-benchmark/cs2a-governor-supervisor.sh --validate-final-handoff '${'$'}RUN_ROOT' '${'$'}GOVERNOR_STATE'"
            count=0
            test ! -e "${'$'}PWD/build/validate-count" || \
              count=${'$'}(cat "${'$'}PWD/build/validate-count")
            printf '%s\n' "${'$'}((count + 1))" >"${'$'}PWD/build/validate-count"
            printf '%s\n' \
              'RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.UntrustedOutput' \
              'GOVERNOR_STATE=/run/revoman-cs2a/governor-state.UntrustedOutput'
            return 37
            ;;
          *readlink\ -f*) printf '%s\n' "${'$'}RUN_ROOT" ;;
          *run-root.txt*) printf '%s\n' "${'$'}RUN_ROOT" ;;
          *implementation-sha.txt*) printf '%s\n' "$IMPLEMENTATION_SHA" ;;
          *executed-script-sha256sums.tsv*)
            printf 'runner\t%s\nsupervisor\t%s\n' \
              "${'$'}(sha256_of "${'$'}CONTROLLED_RUNNER")" \
              "${'$'}(sha256_of "${'$'}SUPERVISOR")"
            ;;
          *operator-post-supervisor-exit.txt*) printf '%s\n' 70 ;;
          *) return 97 ;;
        esac
      }
      rsync() {
        : >"${'$'}PWD/build/rsync-called"
        return 0
      }
      if operator_main --archive-only "${'$'}RUN_ROOT" "${'$'}GOVERNOR_STATE"; then
        exit 98
      else
        test "${'$'}?" -eq 70
      fi
      test "${'$'}(cat "${'$'}PWD/build/validate-count")" = 1
      test ! -e "${'$'}PWD/build/rsync-called"
      test ! -e "${'$'}PWD/build/refresh-called"
      test ! -e "${'$'}PWD/build/driver-called"
      test ! -e "${'$'}PWD/build/install-called"
      test ! -e "${'$'}PWD/build/runner-called"
      test ! -e "${'$'}PWD/build/forbidden-remote-mode"
      """
        .trimIndent()

    val result = run(listOf("/bin/bash", "-c", harness), workspace)

    assertWithMessage(result.output).that(result.exitCode).isEqualTo(0)
    val canonical =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/" + "cs2a-$IMPLEMENTATION_SHA/cs2a.Existing123"
      )
    assertThat(Files.exists(canonical)).isFalse()
    val attempt =
      Path.of(Files.readString(workspace.resolve("build/cs2a-local-evidence-dir.txt")).trim())
    assertThat(attempt.fileName.toString()).startsWith("operator-failure.")
    assertThat(Files.readString(attempt.resolve("meta/operator-failure-phase.txt")))
      .isEqualTo("archive\n")
    assertThat(Files.readString(attempt.resolve("meta/operator-failure-source-exit.txt")))
      .isEqualTo("37\n")
    assertThat(Files.readString(attempt.resolve("meta/remote-evidence-present.txt")))
      .isEqualTo("false\n")
    assertThat(Files.exists(attempt.resolve("manifests"))).isFalse()
    assertThat(Files.exists(attempt.resolve("results"))).isFalse()
    assertThat(Files.exists(attempt.resolve("logs"))).isFalse()
  }

  @Test
  fun `archive only validates final handoff exactly once before copying`() {
    val workspace = Files.createDirectories(temporaryDirectory.resolve("archive-after-validation"))
    write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
    write(workspace.resolve("build/cs2a-supervisor.log"), "trusted supervisor log\n")
    write(workspace.resolve("build/cs2a-supervisor-exit.txt"), "0\n")
    val harness =
      """
      source ${quote(operator)}
      RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.Validated123
      GOVERNOR_STATE=/run/revoman-cs2a/governor-state.Validated123
      prepare_operator_source() { AUTHENTICATED_SOURCE_ROOT=/tmp; }
      prepare_local_driver() { : >"${'$'}PWD/build/driver-called"; return 97; }
      install_remote_bundle() { : >"${'$'}PWD/build/install-called"; return 97; }
      run_remote_supervisor() { : >"${'$'}PWD/build/runner-called"; return 97; }
      verify_remote_bundle() { return 0; }
      refresh_remote_final_handoff() { : >"${'$'}PWD/build/refresh-called"; }
      ssh() {
        case "${'$'}*" in
          *--publish-final-handoff* | *cs2a-controlled-run.sh*)
            : >"${'$'}PWD/build/forbidden-remote-mode"
            return 97
            ;;
          *UntrustedOutput*) : >"${'$'}PWD/build/output-contaminated-markers"; return 97 ;;
          *--validate-final-handoff*)
            test "${'$'}#" = 3
            test "${'$'}1" = -tt
            test "${'$'}2" = "${'$'}REMOTE_HOST"
            test "${'$'}3" = \
              "dzdo /opt/revoman-benchmark/cs2a-governor-supervisor.sh --validate-final-handoff '${'$'}RUN_ROOT' '${'$'}GOVERNOR_STATE'"
            count=0
            test ! -e "${'$'}PWD/build/validate-count" || \
              count=${'$'}(cat "${'$'}PWD/build/validate-count")
            printf '%s\n' "${'$'}((count + 1))" >"${'$'}PWD/build/validate-count"
            printf '%s\n' validate >>"${'$'}PWD/build/archive-order"
            printf '%s\n' \
              'RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.UntrustedOutput' \
              'GOVERNOR_STATE=/run/revoman-cs2a/governor-state.UntrustedOutput'
            ;;
          *readlink\ -f*) printf '%s\n' "${'$'}RUN_ROOT" ;;
          *run-root.txt*) printf '%s\n' "${'$'}RUN_ROOT" ;;
          *implementation-sha.txt*) printf '%s\n' "$IMPLEMENTATION_SHA" ;;
          *executed-script-sha256sums.tsv*)
            printf 'runner\t%s\nsupervisor\t%s\n' \
              "${'$'}(sha256_of "${'$'}CONTROLLED_RUNNER")" \
              "${'$'}(sha256_of "${'$'}SUPERVISOR")"
            ;;
          *operator-post-supervisor-exit.txt*) printf '%s\n' 0 ;;
          *) return 97 ;;
        esac
      }
      rsync() {
        printf '%s\n' rsync >>"${'$'}PWD/build/archive-order"
      }
      publish_archive() {
        cp -- "${'$'}1/meta/operator-resume-validation-exit.txt" \
          "${'$'}PWD/build/captured-resume-validation-exit.txt"
        cp -- "${'$'}1/meta/operator-final-exit.txt" \
          "${'$'}PWD/build/captured-final-exit.txt"
      }
      operator_main --archive-only "${'$'}RUN_ROOT" "${'$'}GOVERNOR_STATE"
      test "${'$'}(cat "${'$'}PWD/build/validate-count")" = 1
      test "${'$'}(sed -n '1p' "${'$'}PWD/build/archive-order")" = validate
      test "${'$'}(grep -c '^rsync${'$'}' "${'$'}PWD/build/archive-order")" = 4
      test "${'$'}(cat "${'$'}PWD/build/captured-resume-validation-exit.txt")" = 0
      test "${'$'}(cat "${'$'}PWD/build/captured-final-exit.txt")" = 0
      test ! -e "${'$'}PWD/build/refresh-called"
      test ! -e "${'$'}PWD/build/driver-called"
      test ! -e "${'$'}PWD/build/install-called"
      test ! -e "${'$'}PWD/build/runner-called"
      test ! -e "${'$'}PWD/build/forbidden-remote-mode"
      test ! -e "${'$'}PWD/build/output-contaminated-markers"
      """
        .trimIndent()

    val result = run(listOf("/bin/bash", "-c", harness), workspace)

    assertWithMessage(result.output).that(result.exitCode).isEqualTo(0)
  }

  @Test
  fun `fresh run publishes final handoff exactly once before archive`() {
    val workspace = Files.createDirectories(temporaryDirectory.resolve("run-single-refresh"))
    write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
    val harness =
      """
      source ${quote(operator)}
      prepare_operator_source() { LOCAL_DRIVER=/bin/false; }
      install_remote_bundle() { return 0; }
      run_remote_supervisor() {
        printf '%s\n' \
          'RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.Fresh123' \
          'GOVERNOR_STATE=/run/revoman-cs2a/governor-state.Fresh123' \
          >"${'$'}PWD/build/cs2a-supervisor.log"
        printf '%s\n' 0 >"${'$'}PWD/build/cs2a-supervisor-exit.txt"
      }
      persist_original_post_status() { return 0; }
      refresh_remote_final_handoff() {
        count=0
        test ! -e "${'$'}PWD/build/refresh-count" || count=${'$'}(cat "${'$'}PWD/build/refresh-count")
        count=${'$'}((count + 1))
        printf '%s\n' "${'$'}count" >"${'$'}PWD/build/refresh-count"
      }
      archive_remote_attempt() {
        test "${'$'}(cat "${'$'}PWD/build/refresh-count")" = 1
        : >"${'$'}PWD/build/archive-called"
      }
      operator_main
      test "${'$'}(cat "${'$'}PWD/build/refresh-count")" = 1
      test -f "${'$'}PWD/build/archive-called"
      """
        .trimIndent()

    val result = run(listOf("/bin/bash", "-c", harness), workspace)

    assertWithMessage(result.output).that(result.exitCode).isEqualTo(0)
  }

  @Test
  fun `complete deterministic archive passes central semantic acceptance`() {
    val fixture = createCompleteArchiveFixture("complete-valid")

    assertBashFunctionSucceeds(
      "validate_archive_semantics ${quote(fixture.archive)} $IMPLEMENTATION_SHA " +
        "${quote(fixture.driver)} ${fixture.policySha256}"
    )
  }

  @Test
  fun `public selection ignores the publication-time validation boolean`() {
    val fixture = createCompleteArchiveFixture("selection-publication-boolean")
    write(fixture.archive.resolve("meta/local-validation-passed.txt"), "false\n")

    val result = runPublicSelection(fixture)

    assertWithMessage(result.output).that(result.exitCode).isEqualTo(0)
  }

  @Test
  fun `public selection does not require a publication-time driver log`() {
    val fixture = createCompleteArchiveFixture("selection-without-driver-log")
    assertThat(Files.exists(fixture.archive.resolve("meta/operator-local-validation-driver.log")))
      .isFalse()

    val result = runPublicSelection(fixture)

    assertWithMessage(result.output).that(result.exitCode).isEqualTo(0)
  }

  @Test
  fun `public selection requires immutable publication and semantic acceptance call sites`() {
    val source = Files.readString(operator)
    val publicationCall =
      "validate_persisted_publication \"${'$'}attempt\" \"${'$'}implementation\" " +
        "\"${'$'}evidence_sha\" || return 1"
    val publicationMutant = source.replace(publicationCall, ":")
    assertThat(publicationMutant).isNotEqualTo(source)
    val publicationFixture = createCompleteArchiveFixture("selection-publication-callsite")
    val mutateCommittedAttempt: (Path) -> Unit = { attempt ->
      write(attempt.resolve("meta/local-validation-passed.txt"), "true\n")
    }
    val rejectedPublication =
      runPublicSelection(publicationFixture, committedAttemptMutation = mutateCommittedAttempt)
    assertWithMessage(rejectedPublication.output).that(rejectedPublication.exitCode).isNotEqualTo(0)
    val bypassedPublication =
      runPublicSelection(
        publicationFixture,
        operatorTransform = { it.replace(publicationCall, ":") },
        committedAttemptMutation = mutateCommittedAttempt,
      )
    assertWithMessage(bypassedPublication.output).that(bypassedPublication.exitCode).isEqualTo(0)

    val semanticCall =
      "validate_archive \"${'$'}attempt\" \"${'$'}implementation\" \"${'$'}driver\""
    val semanticMutant = source.replace(semanticCall, ":")
    assertThat(semanticMutant).isNotEqualTo(source)
    val semanticFixture = createCompleteArchiveFixture("selection-semantic-callsite")
    val semanticDriver = semanticFixture.driver
    write(
      semanticDriver,
      """
      #!/usr/bin/env bash
      case "${'$'}1" in
        verify) exit 0 ;;
        compare) exit 37 ;;
        *) exit 2 ;;
      esac
      """
        .trimIndent() + "\n",
    )
    semanticDriver.toFile().setExecutable(true, false)
    val rejectedSemantics = runPublicSelection(semanticFixture)
    assertWithMessage(rejectedSemantics.output).that(rejectedSemantics.exitCode).isNotEqualTo(0)
    val bypassedSemantics =
      runPublicSelection(
        semanticFixture,
        operatorTransform = { it.replace(semanticCall, ":") },
      )
    assertWithMessage(bypassedSemantics.output).that(bypassedSemantics.exitCode).isEqualTo(0)
  }

  @Test
  fun `campaign protocol rejects a changed deterministic seed`() {
    val fixture = createCompleteArchiveFixture("campaign-seed-red")
    mutateJson(
      fixture.archive.resolve("results/cold-aa.json"),
      ".configuration.seed = 5928239383101656624",
    )
    refreshRemoteEvidenceInventory(fixture.archive)
    refreshRemoteByteInventory(fixture.archive)

    assertBashFunctionFails(
      "validate_archive_semantics ${quote(fixture.archive)} $IMPLEMENTATION_SHA " +
        "${quote(fixture.driver)} ${fixture.policySha256}",
      "campaign seed",
    )
  }

  @Test
  fun `campaign protocol pins configuration harness environment workload series and target order`() {
    val fixture = createCompleteArchiveFixture("campaign-field-matrix")
    val result = fixture.archive.resolve("results/cold-aa.json")
    val original = Files.readString(result)
    val invocation =
      "validate_campaign_identity ${quote(fixture.archive)} results/cold-aa.json COLD " +
        "baseline-b-cs2a baseline-83f3cd70 $BASELINE_SHA $IMPLEMENTATION_SHA"
    assertBashFunctionSucceeds(invocation)
    val mutations =
      linkedMapOf(
        "schema" to ".schema = \"future\"",
        "intent" to ".intent = \"LOCAL\"",
        "metric passes" to ".configuration.metricPasses = [\"LATENCY\"]",
        "accepted blocks" to ".configuration.requestedAcceptedBlocks = 49",
        "forks" to ".configuration.forksPerBlock = 2",
        "warmups" to ".configuration.warmupIterations = 1",
        "iterations" to ".configuration.measurementIterations = 2",
        "configuration target" to ".configuration.targets[0].targetId = \"other\"",
        "harness commit" to ".harness.commit = \"${"0".repeat(40)}\"",
        "harness tree" to ".harness.tree = \"${"0".repeat(40)}\"",
        "harness dirty" to ".harness.dirty = true",
        "harness artifact" to ".harness.artifacts = []",
        "adapter order" to ".harness.adapters |= reverse",
        "policy" to ".environment.policySha256 = \"${"0".repeat(64)}\"",
        "host" to ".environment.hostFingerprintSha256 = \"${"0".repeat(64)}\"",
        "governor" to ".environment.governor = \"powersave\"",
        "runtime java" to ".environment.jdk.javaHome = \"/tmp/java\"",
        "runtime flags missing" to ".environment.jdk.jvmFlags = []",
        "runtime flag changed" to ".environment.jdk.jvmFlags = [\"-Xmx1g\"]",
        "runtime flags extra" to ".environment.jdk.jvmFlags += [\"-Xmx1g\"]",
        "workload id" to ".workloads[0].id = \"future\"",
        "workload contract" to ".workloads[0].contractSha256 = \"${"0".repeat(64)}\"",
        "fixture" to ".workloads[0].fixtureSha256 = \"${"0".repeat(64)}\"",
        "workload mode" to ".workloads[0].mode = \"WARM\"",
        "metric" to ".workloads[0].metricSeries[0].metric = \"OTHER\"",
        "provider" to ".workloads[0].metricSeries[0].provider = \"other/v1\"",
        "unit" to ".workloads[0].metricSeries[0].unit = \"BYTES\"",
        "accepted balance" to ".workloads[0].metricSeries[0].blocks[0].accepted = false",
        "block sequence" to ".workloads[0].metricSeries[0].blocks[0].blockId = 99",
        "target membership" to ".workloads[0].metricSeries[0].blocks[0].targetOrder[1] = \"other\"",
        "duplicate target" to
          ".workloads[0].metricSeries[0].blocks[0].targetOrder = " +
            "[\"baseline-a-cs2a\",\"baseline-a-cs2a\"]",
        "unbalanced accepted first position" to
          ".workloads[0].metricSeries[0].blocks |= " +
            "map(.targetOrder = [\"baseline-a-cs2a\",\"baseline-b-cs2a\"])",
        "fork" to ".workloads[0].metricSeries[0].blocks[0].observations[0].fork = 1",
        "target object order" to ".targets |= reverse",
      )

    mutations.forEach { (label, filter) ->
      mutateJson(result, filter)
      assertBashFunctionFails(invocation, label)
      write(result, original)
    }
  }

  @Test
  fun `campaign target projection and artifact inventories are exact`() {
    val fixture = createCompleteArchiveFixture("campaign-projection-matrix")
    val cold = fixture.archive.resolve("results/cold-aa.json")
    val originalCold = Files.readString(cold)
    val projectionInvocation =
      "validate_target_projection ${quote(cold)} baseline-a-cs2a " +
        "${quote(fixture.archive.resolve("manifests/baseline-a.json"))} baseline-83f3cd70"
    linkedMapOf(
        "tree" to ".targets[0].gitTree = \"${"0".repeat(40)}\"",
        "gradle" to ".targets[0].gradleVersion = \"0\"",
        "wrapper" to ".targets[0].wrapperSha256 = \"${"0".repeat(64)}\"",
        "build JDK" to ".targets[0].buildJdk.javaHome = \"/tmp/java\"",
        "classpath logical id" to ".targets[0].classpath[0].logicalId = \"other\"",
        "classpath size" to ".targets[0].classpath[0].sizeBytes = 124",
        "classpath hash" to ".targets[0].classpath[0].sha256 = \"${"0".repeat(64)}\"",
        "adapter provenance" to ".targets[0].adapter.sourceSha256 = \"${"0".repeat(64)}\"",
      )
      .forEach { (label, filter) ->
        mutateJson(cold, filter)
        assertBashFunctionFails(projectionInvocation, label)
        write(cold, originalCold)
      }

    mutateJson(cold, ".workloads[0].metricSeries[1].artifacts |= .[1:]")
    assertBashFunctionFails(
      "validate_campaign_artifact_shape ${quote(fixture.archive)} results/cold-aa.json COLD cold-aa",
      "missing result artifact",
    )
    write(cold, originalCold)
    mutateJson(cold, ".workloads[0].metricSeries[1].artifacts |= . + [.[0]]")
    assertBashFunctionFails(
      "validate_campaign_artifact_shape ${quote(fixture.archive)} results/cold-aa.json COLD cold-aa",
      "extra result artifact",
    )
    write(cold, originalCold)

    val inventory = fixture.archive.resolve("meta/artifact-inventory.tsv")
    val originalInventory = Files.readString(inventory)
    write(inventory, originalInventory.lineSequence().drop(1).joinToString("\n", postfix = "\n"))
    assertBashFunctionFails(
      "validate_campaign_artifact_inventory ${quote(fixture.archive)}",
      "missing inventory tuple",
    )
    write(inventory, originalInventory + "artifacts/future/unrelated.bin\t1\n")
    assertBashFunctionFails(
      "validate_campaign_artifact_inventory ${quote(fixture.archive)}",
      "extra inventory tuple",
    )
  }

  @Test
  fun `public selection rejects unknown status evidence with self-consistent inventories`() {
    val fixture = createCompleteArchiveFixture("selection-unknown-status")

    val accepted = runPublicSelection(fixture)
    assertWithMessage(accepted.output).that(accepted.exitCode).isEqualTo(0)

    write(fixture.archive.resolve("meta/comparison-future-retained-exit.txt"), "0\n")
    refreshRemoteByteInventory(fixture.archive)
    assertBashFunctionSucceeds("write_root_checksum_inventory ${quote(fixture.archive)}")

    val result = runPublicSelection(fixture)

    assertWithMessage(result.output).that(result.exitCode).isNotEqualTo(0)
  }

  @Test
  fun `stage schema accepts exact fail-fast command prefixes`() {
    val coldFailure = createStageFixture("prefix-cold-failure", "setup")
    listOf("baseline-a.json", "baseline-b.json", "candidate.json").forEach { file ->
      write(coldFailure.resolve("manifests/$file"), "{}\n")
    }
    write(coldFailure.resolve("meta/cold-aa-exit.txt"), "70\n")
    assertBashFunctionSucceeds("validate_stage_schema ${quote(coldFailure)}")

    val warmFailure = createStageFixture("prefix-warm-failure", "setup")
    listOf("baseline-a.json", "baseline-b.json", "candidate.json").forEach { file ->
      write(warmFailure.resolve("manifests/$file"), "{}\n")
    }
    write(warmFailure.resolve("results/cold-aa.json"), "{}\n")
    write(warmFailure.resolve("meta/cold-aa-exit.txt"), "0\n")
    write(warmFailure.resolve("meta/warm-aa-exit.txt"), "70\n")
    assertBashFunctionSucceeds("validate_stage_schema ${quote(warmFailure)}")

    val candidateFailure = createStageFixture("prefix-candidate-failure", "aa-compared")
    write(candidateFailure.resolve("results/cold-candidate.json"), "{}\n")
    write(candidateFailure.resolve("meta/cold-candidate-exit.txt"), "0\n")
    write(candidateFailure.resolve("meta/warm-candidate-exit.txt"), "70\n")
    assertBashFunctionSucceeds("validate_stage_schema ${quote(candidateFailure)}")

    write(candidateFailure.resolve("results/retained-candidate.json"), "unexpected\n")
    assertBashFunctionFails(
      "validate_stage_schema ${quote(candidateFailure)}",
      "non-prefix candidate file",
    )
  }

  @Test
  fun `central semantic acceptance rejects independent status policy pass provenance and command mutations`() {
    val mutations =
      linkedMapOf<String, (ArchiveFixture) -> Unit>(
        "restoration failure" to
          { fixture ->
            write(fixture.archive.resolve("meta/supervisor/restoration-failed.txt"), "true\n")
          },
        "child status mismatch" to
          { fixture ->
            write(fixture.archive.resolve("meta/supervisor/child-or-supervisor-status.txt"), "1\n")
          },
        "capture status failure" to
          { fixture ->
            write(fixture.archive.resolve("meta/warm-candidate-exit.txt"), "70\n")
            refreshRemoteByteInventory(fixture.archive)
          },
        "run-root mismatch" to
          { fixture ->
            write(
              fixture.archive.resolve("meta/supervisor/run-root.txt"),
              "/opt/revoman-benchmark/runs/cs2a.other\n",
            )
          },
        "supervisor implementation mismatch" to
          { fixture ->
            write(
              fixture.archive.resolve("meta/supervisor/implementation-sha.txt"),
              "${"f".repeat(40)}\n",
            )
          },
        "supervisor core differs from final handoff" to
          { fixture ->
            write(
              fixture.archive.resolve("meta/supervisor-core/implementation-sha.txt"),
              "${"e".repeat(40)}\n",
            )
          },
        "lock not released" to
          { fixture ->
            write(fixture.archive.resolve("meta/supervisor/lock-released.txt"), "false\n")
          },
        "executed runner provenance" to
          { fixture ->
            val supervisorSha = sha256(fixture.archive.resolve("meta/cs2a-governor-supervisor.sh"))
            write(
              fixture.archive.resolve("meta/supervisor/executed-script-sha256sums.tsv"),
              "runner\t${"0".repeat(64)}\nsupervisor\t$supervisorSha\n",
            )
          },
        "raw policy with self-consistent rewritten inventory" to
          { fixture ->
            write(fixture.archive.resolve("meta/controlled-host.json"), "{\"mutated\":true}\n")
            write(
              fixture.archive.resolve("meta/policy-sha256.txt"),
              "${sha256(fixture.archive.resolve("meta/controlled-host.json"))}  " +
                "/opt/revoman-benchmark/controlled-host.json\n",
            )
            refreshRemoteByteInventory(fixture.archive)
          },
        "archived comparison decision with self-consistent rewritten inventory" to
          { fixture ->
            write(
              fixture.archive.resolve("results/comparison-candidate-warm.json"),
              "{\"overall\":\"FAIL\"}\n",
            )
            refreshRemoteEvidenceInventory(fixture.archive)
            refreshRemoteByteInventory(fixture.archive)
          },
        "extra command stream with self-consistent rewritten inventory" to
          { fixture ->
            write(fixture.archive.resolve("logs/unlisted.stdout"), "extra\n")
            refreshCommandOutputInventory(fixture.archive)
            refreshRemoteByteInventory(fixture.archive)
          },
      )

    mutations.forEach { (label, mutate) ->
      val fixture = createCompleteArchiveFixture("mutation-${label.replace(' ', '-')}")
      mutate(fixture)
      assertBashFunctionFails(
        "validate_archive_semantics ${quote(fixture.archive)} $IMPLEMENTATION_SHA " +
          "${quote(fixture.driver)} ${fixture.policySha256}",
        label,
      )
    }
  }

  @Test
  fun `local driver verifies result files and never copied target manifests`() {
    val archive = Files.createDirectories(temporaryDirectory.resolve("verify-results"))
    Files.createDirectories(archive.resolve("manifests"))
    val results = Files.createDirectories(archive.resolve("results"))
    write(archive.resolve("manifests/candidate.json"), "{}\n")
    write(results.resolve("cold-aa.json"), "{}\n")
    write(results.resolve("warm-candidate.json"), "{}\n")
    val log = archive.resolve("driver.log")
    val driver = archive.resolve("fake-driver.sh")
    write(
      driver,
      "#!/usr/bin/env bash\nprintf '%s\\n' \"\$*\" >>${quote(log)}\n",
    )
    driver.toFile().setExecutable(true, false)

    assertBashFunctionSucceeds("verify_result_files ${quote(archive)} ${quote(driver)}")

    val invocations = Files.readString(log)
    assertThat(invocations).contains("verify --input ${archive.resolve("results/cold-aa.json")}")
    assertThat(invocations)
      .contains("verify --input ${archive.resolve("results/warm-candidate.json")}")
    assertThat(invocations).doesNotContain("manifests")
  }

  @Test
  fun `executed runner supervisor and implementation provenance are exact`() {
    val implementation = "c".repeat(40)
    val archive = Files.createDirectories(temporaryDirectory.resolve("provenance"))
    val meta = Files.createDirectories(archive.resolve("meta"))
    val supervisorMeta = Files.createDirectories(meta.resolve("supervisor"))
    val runner = meta.resolve("cs2a-controlled-run.sh")
    val supervisor = meta.resolve("cs2a-governor-supervisor.sh")
    write(runner, "runner\n")
    write(supervisor, "supervisor\n")
    write(meta.resolve("controlled-uid.txt"), "1234\n")
    val rows = "runner\t${sha256(runner)}\n" + "supervisor\t${sha256(supervisor)}\n"
    write(supervisorMeta.resolve("executed-script-sha256sums.tsv"), rows)
    write(
      supervisorMeta.resolve("authenticated-handoff.tsv"),
      "implementation\t$implementation\nuid\t1234\n" + rows,
    )

    assertBashFunctionSucceeds("validate_executed_provenance ${quote(archive)} $implementation")

    write(
      supervisorMeta.resolve("executed-script-sha256sums.tsv"),
      "runner\t${"d".repeat(64)}\nsupervisor\t${sha256(supervisor)}\n",
    )
    assertBashFunctionFails(
      "validate_executed_provenance ${quote(archive)} $implementation",
      "wrong runner SHA",
    )
  }

  @Test
  fun `governor restoration restores exact captured value and rejects path escape`() {
    val sysRoot = temporaryDirectory.resolve("sys")
    val governor =
      Files.createDirectories(sysRoot.resolve("cpu0/cpufreq")).resolve("scaling_governor")
    write(governor, "performance\n")
    val inventory = temporaryDirectory.resolve("original-governors.tsv")
    write(inventory, "$governor\tpowersave\n")

    assertSupervisorFunctionSucceeds("restore_governors ${quote(inventory)} ${quote(sysRoot)}")
    assertThat(Files.readString(governor)).isEqualTo("powersave\n")

    val escaped = temporaryDirectory.resolve("escape")
    write(escaped, "performance\n")
    write(inventory, "$escaped\tpowersave\n")
    assertSupervisorFunctionFails(
      "restore_governors ${quote(inventory)} ${quote(sysRoot)}",
      "governor path escape",
    )
  }

  @Test
  fun `supervisor rejects missing and duplicate run-root markers and records signals`() {
    val output = temporaryDirectory.resolve("supervisor-output.log")
    write(output, "ordinary output\n")
    assertSupervisorFunctionFails(
      "extract_run_root_marker ${quote(output)}",
      "missing run-root marker",
    )
    write(
      output,
      "RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.First\n" +
        "RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.Second\n",
    )
    assertSupervisorFunctionFails(
      "extract_run_root_marker ${quote(output)}",
      "duplicate run-root marker",
    )
    write(output, "RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.Exact123\n")
    assertSupervisorFunctionSucceeds(
      "test \"\$(extract_run_root_marker ${quote(output)})\" = " +
        "/opt/revoman-benchmark/runs/cs2a.Exact123"
    )
    assertSupervisorFunctionSucceeds(
      "CHILD_PGID=; handle_signal TERM 143; test \"\$SIGNAL_STATUS\" = 143"
    )
  }

  @Test
  fun `publication marker recovery is idempotent after canonical rename`() {
    val implementation = "e".repeat(40)
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("publication-workspace")).toRealPath()
    val canonical =
      Files.createDirectories(
        workspace.resolve(
          "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/cs2a.recovered"
        )
      )
    write(canonical.resolve("payload.txt"), "preserved\n")
    val marker = Files.createDirectories(workspace.resolve("build")).resolve("marker.txt")
    val stage = canonical.parent.resolve(".cs2a-archive-stage.unused")
    val command =
      "source ${quote(operator)}; " +
        "write_root_checksum_inventory ${quote(canonical)}; " +
        "publish_archive ${quote(stage)} ${quote(canonical)} ${quote(marker)} $implementation"

    assertProcessSucceeds(listOf("/bin/bash", "-c", command), workspace)
    assertThat(Files.readString(marker).trim()).isEqualTo(canonical.toString())
    assertProcessSucceeds(listOf("/bin/bash", "-c", command), workspace)
    assertThat(Files.readString(canonical.resolve("payload.txt"))).isEqualTo("preserved\n")
  }

  @Test
  fun `canonical publication recovery rejects corruption even when marker already exists`() {
    val implementation = "f".repeat(40)
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("corrupt-recovery")).toRealPath()
    val canonical =
      Files.createDirectories(
        workspace.resolve(
          "docs/superpowers/benchmarks/results/v1/cs2a-$implementation/cs2a.corrupt"
        )
      )
    write(canonical.resolve("payload.txt"), "original\n")
    val marker = Files.createDirectories(workspace.resolve("build")).resolve("marker.txt")
    val stage = canonical.parent.resolve(".cs2a-archive-stage.unused")
    val initialize = "source ${quote(operator)}; write_root_checksum_inventory ${quote(canonical)}"
    val publish =
      "source ${quote(operator)}; " +
        "publish_archive ${quote(stage)} ${quote(canonical)} ${quote(marker)} $implementation"
    assertProcessSucceeds(listOf("/bin/bash", "-c", initialize), workspace)
    assertProcessSucceeds(listOf("/bin/bash", "-c", publish), workspace)

    write(canonical.resolve("payload.txt"), "corrupted\n")
    val recovered = run(listOf("/bin/bash", "-c", publish), workspace)
    assertWithMessage(recovered.output).that(recovered.exitCode).isNotEqualTo(0)
  }

  @Test
  fun `canonical publication recovery rejects a symlink even with valid target checksums`() {
    val implementation = "1".repeat(40)
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("symlink-recovery")).toRealPath()
    val parent =
      Files.createDirectories(
        workspace.resolve("docs/superpowers/benchmarks/results/v1/cs2a-$implementation")
      )
    val external = Files.createDirectories(temporaryDirectory.resolve("external-canonical"))
    write(external.resolve("payload.txt"), "external\n")
    assertProcessSucceeds(
      listOf(
        "/bin/bash",
        "-c",
        "source ${quote(operator)}; write_root_checksum_inventory ${quote(external)}",
      ),
      workspace,
    )
    val canonical = parent.resolve("cs2a.symlink")
    Files.createSymbolicLink(canonical, external)
    val marker = Files.createDirectories(workspace.resolve("build")).resolve("marker.txt")
    val stage = parent.resolve(".cs2a-archive-stage.unused")
    val recovered =
      run(
        listOf(
          "/bin/bash",
          "-c",
          "source ${quote(operator)}; " +
            "publish_archive ${quote(stage)} ${quote(canonical)} ${quote(marker)} $implementation",
        ),
        workspace,
      )

    assertWithMessage(recovered.output).that(recovered.exitCode).isNotEqualTo(0)
    assertThat(Files.exists(marker)).isFalse()
  }

  @Test
  fun `canonical publication discovers platform GNU tools without weakening no-replace`() {
    val implementation = "9".repeat(40)
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("portable-publication")).toRealPath()
    val parent =
      Files.createDirectories(
        workspace.resolve("docs/superpowers/benchmarks/results/v1/cs2a-$implementation")
      )
    val stage = Files.createDirectories(parent.resolve(".cs2a-archive-stage.Portable123"))
    write(stage.resolve("payload.txt"), "portable\n")
    val canonical = parent.resolve("cs2a.portable")
    val marker = Files.createDirectories(workspace.resolve("build")).resolve("marker.txt")

    assertProcessSucceeds(
      listOf(
        "/bin/bash",
        "-c",
        "source ${quote(operator)}; " +
          "publish_archive ${quote(stage)} ${quote(canonical)} ${quote(marker)} $implementation",
      ),
      workspace,
    )

    assertThat(Files.readString(canonical.resolve("payload.txt"))).isEqualTo("portable\n")
    assertThat(Files.exists(stage)).isFalse()
    assertThat(Files.readString(marker).trim()).isEqualTo(canonical.toString())
  }

  @Test
  fun `publication tool discovery selects exact Darwin and Linux command names`() {
    val fakeBin = Files.createDirectories(temporaryDirectory.resolve("platform-publication-tools"))
    val realMove = run(listOf("/bin/bash", "-c", "command -v gmv || command -v mv")).output.trim()
    val realStat =
      run(listOf("/bin/bash", "-c", "command -v gstat || command -v stat")).output.trim()
    val fakeMove =
      """
      #!/bin/sh
      if test "${'$'}1" = --version; then
        printf '%s\n' 'mv (GNU coreutils) 9.99'
        exit 0
      fi
      exec ${quote(Path.of(realMove))} "${'$'}@"
      """
        .trimIndent() + "\n"
    val fakeStat =
      """
      #!/bin/sh
      if test "${'$'}1" = --version; then
        printf '%s\n' 'stat (GNU coreutils) 9.99'
        exit 0
      fi
      exec ${quote(Path.of(realStat))} "${'$'}@"
      """
        .trimIndent() + "\n"
    listOf("mv", "gmv").forEach { name ->
      write(fakeBin.resolve(name), fakeMove)
      fakeBin.resolve(name).toFile().setExecutable(true, false)
    }
    listOf("stat", "gstat").forEach { name ->
      write(fakeBin.resolve(name), fakeStat)
      fakeBin.resolve(name).toFile().setExecutable(true, false)
    }
    val harness =
      """
      PATH=${quote(fakeBin)}:${'$'}PATH
      export PATH
      source ${quote(operator)}
      uname() { printf '%s\n' "${'$'}FAKE_OS"; }
      assert_selection() {
        FAKE_OS=${'$'}1
        discover_publication_tools
        case "${'$'}FAKE_OS" in
          Darwin)
            test "${'$'}PUBLICATION_MV" = ${quote(fakeBin.resolve("gmv"))}
            test "${'$'}PUBLICATION_STAT" = ${quote(fakeBin.resolve("gstat"))}
            ;;
          Linux)
            test "${'$'}PUBLICATION_MV" = ${quote(fakeBin.resolve("mv"))}
            test "${'$'}PUBLICATION_STAT" = ${quote(fakeBin.resolve("stat"))}
            ;;
          *) exit 98 ;;
        esac
      }
      assert_selection Darwin
      assert_selection Linux
      """
        .trimIndent()

    assertProcessSucceeds(listOf("/bin/bash", "-c", harness))
  }

  @Test
  fun `publication tool discovery accepts a no-replace conflict status with intact directories`() {
    val result =
      runPublicationToolDiscoveryProbe(
        "nonzero-intact",
        """
        previous=
        current=
        for argument in "${'$'}@"; do
          previous=${'$'}current
          current=${'$'}argument
        done
        test -d "${'$'}previous" && test -d "${'$'}current" || exit 97
        exit 1
        """,
      )

    assertWithMessage(result.output).that(result.exitCode).isEqualTo(0)
  }

  @Test
  fun `publication tool discovery rejects exit one when a probed directory is removed`() {
    val result =
      runPublicationToolDiscoveryProbe(
        "nonzero-mutating",
        """
        previous=
        current=
        for argument in "${'$'}@"; do
          previous=${'$'}current
          current=${'$'}argument
        done
        rmdir "${'$'}previous"
        exit 1
        """,
      )

    assertWithMessage(result.output).that(result.exitCode).isNotEqualTo(0)
  }

  @Test
  fun `publication tool discovery rejects exit two when probed directories remain intact`() {
    val result = runPublicationToolDiscoveryProbe("unexpected-status", "exit 2")

    assertWithMessage(result.output).that(result.exitCode).isNotEqualTo(0)
  }

  @Test
  fun `publication tool discovery rejects a GNU-labelled backend with broken no-replace semantics`() {
    val fakeBin = Files.createDirectories(temporaryDirectory.resolve("mutated-publication-tools"))
    val realMove = run(listOf("/bin/bash", "-c", "command -v gmv || command -v mv")).output.trim()
    val realStat =
      run(listOf("/bin/bash", "-c", "command -v gstat || command -v stat")).output.trim()
    val workingMove =
      """
      #!/bin/sh
      if test "${'$'}1" = --version; then
        printf '%s\n' 'mv (GNU coreutils) 9.99'
        exit 0
      fi
      exec ${quote(Path.of(realMove))} "${'$'}@"
      """
        .trimIndent() + "\n"
    val brokenMove =
      """
      #!/bin/sh
      if test "${'$'}1" = --version; then
        printf '%s\n' 'mv (GNU coreutils) 9.99'
        exit 0
      fi
      previous=
      current=
      for argument in "${'$'}@"; do
        previous=${'$'}current
        current=${'$'}argument
      done
      rmdir "${'$'}previous"
      exit 0
      """
        .trimIndent() + "\n"
    val fakeStat =
      """
      #!/bin/sh
      if test "${'$'}1" = --version; then
        printf '%s\n' 'stat (GNU coreutils) 9.99'
      else
        exec ${quote(Path.of(realStat))} "${'$'}@"
      fi
      """
        .trimIndent() + "\n"
    listOf("mv", "gmv").forEach { name ->
      write(fakeBin.resolve(name), workingMove)
      fakeBin.resolve(name).toFile().setExecutable(true, false)
    }
    listOf("stat", "gstat").forEach { name ->
      write(fakeBin.resolve(name), fakeStat)
      fakeBin.resolve(name).toFile().setExecutable(true, false)
    }
    val brokenBackend = temporaryDirectory.resolve("broken-mv")
    write(brokenBackend, brokenMove)
    val harness =
      """
      PATH=${quote(fakeBin)}:${'$'}PATH
      export PATH
      source ${quote(operator)}
      discover_publication_tools
      cp -- ${quote(brokenBackend)} "${'$'}PUBLICATION_MV"
      chmod +x "${'$'}PUBLICATION_MV"
      if discover_publication_tools; then exit 98; fi
      """
        .trimIndent()

    val result = run(listOf("/bin/bash", "-c", harness))

    assertWithMessage(result.output).that(result.exitCode).isEqualTo(0)
  }

  @Test
  fun `canonical archive and marker publication reject raced destinations without clobbering`() {
    listOf("canonical", "marker").forEach { race ->
      val implementation = "2".repeat(40)
      val workspace =
        Files.createDirectories(temporaryDirectory.resolve("publication-race-$race")).toRealPath()
      val parent =
        Files.createDirectories(
          workspace.resolve("docs/superpowers/benchmarks/results/v1/cs2a-$implementation")
        )
      val stage = Files.createDirectories(parent.resolve(".cs2a-archive-stage.Race1234"))
      write(stage.resolve("payload.txt"), "authenticated\n")
      val canonical = parent.resolve("cs2a.race")
      val marker = Files.createDirectories(workspace.resolve("build")).resolve("marker.txt")
      val victim = workspace.resolve("victim.txt")
      write(victim, "unchanged\n")
      val harness =
        """
        source "${'$'}1"
        RACE=${'$'}2
        stage=${'$'}3
        canonical=${'$'}4
        marker=${'$'}5
        victim=${'$'}6
        implementation=${'$'}7
        if test "${'$'}RACE" = canonical; then
          before_archive_directory_publish() { mkdir "${'$'}canonical"; }
        else
          before_archive_marker_publish() { ln -s "${'$'}victim" "${'$'}marker"; }
        fi
        publish_archive "${'$'}stage" "${'$'}canonical" "${'$'}marker" "${'$'}implementation"
        """
          .trimIndent()
      val result =
        run(
          listOf(
            "/bin/bash",
            "-c",
            harness,
            "race-harness",
            operator.toString(),
            race,
            stage.toString(),
            canonical.toString(),
            marker.toString(),
            victim.toString(),
            implementation,
          ),
          workspace,
        )

      assertWithMessage("$race\n${result.output}").that(result.exitCode).isNotEqualTo(0)
      assertThat(Files.readString(victim)).isEqualTo("unchanged\n")
      if (race == "canonical") {
        assertThat(Files.list(canonical).use { it.count() }).isEqualTo(0)
      } else {
        assertThat(Files.isSymbolicLink(marker)).isTrue()
      }
    }
  }

  @Test
  fun `operator accepts only exact archive resume paths and exposes all four modes`() {
    assertBashFunctionSucceeds(
      "validate_resume_paths /opt/revoman-benchmark/runs/cs2a.Abc123 " +
        "/run/revoman-cs2a/governor-state.Xyz789"
    )
    assertBashFunctionFails(
      "validate_resume_paths /opt/revoman-benchmark/runs/cs2a.A/../escape " +
        "/run/revoman-cs2a/governor-state.X",
      "unsafe resume path",
    )
    val unsafeMarkers = temporaryDirectory.resolve("unsafe-operator-markers.log")
    write(
      unsafeMarkers,
      "RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.A/../escape\n" +
        "GOVERNOR_STATE=/run/revoman-cs2a/governor-state.X/../escape\n",
    )
    assertBashFunctionFails(
      "extract_supervisor_marker ${quote(unsafeMarkers)} RUN_ROOT",
      "unsafe run-root marker",
    )
    assertBashFunctionFails(
      "extract_supervisor_marker ${quote(unsafeMarkers)} GOVERNOR_STATE",
      "unsafe governor-state marker",
    )

    OperatorSourceContract.assertComplete(sourceBundle)
    OPERATOR_WITNESS_MUTATIONS.forEach { (description, file, witness) ->
      val mutated = sourceBundle.toMutableMap()
      mutated[file] = mutated.getValue(file).replace(witness, "")
      try {
        OperatorSourceContract.assertComplete(mutated)
        throw AssertionError("mutation survived contract: $description; witness=$witness")
      } catch (_: IllegalStateException) {
        // Expected: each security witness independently kills the contract.
      }
    }
  }

  private fun assertBashFunctionSucceeds(invocation: String) {
    assertProcessSucceeds(
      listOf("/bin/bash", "-c", "source ${quote(operator)}; $invocation"),
      Path.of("").toAbsolutePath(),
    )
  }

  private fun runOperatorMainCallSiteHarness(script: Path, name: String): ProcessResult {
    val workspace = Files.createDirectories(temporaryDirectory.resolve("operator-main-$name"))
    write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
    val harness =
      """
      source "${'$'}1"
      prepare_operator_source() { LOCAL_DRIVER=/bin/false; }
      install_remote_bundle() { return 0; }
      run_remote_supervisor() {
        printf '%s\n' \
          'RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.CallSite123' \
          'GOVERNOR_STATE=/run/revoman-cs2a/governor-state.CallSite123' \
          >"${'$'}PWD/build/cs2a-supervisor.log"
        printf '%s\n' 0 >"${'$'}PWD/build/cs2a-supervisor-exit.txt"
      }
      persist_original_post_status() { : >"${'$'}PWD/build/persist-called"; }
      refresh_remote_final_handoff() { : >"${'$'}PWD/build/refresh-called"; }
      archive_remote_attempt() { : >"${'$'}PWD/build/archive-called"; }
      ssh() { return 97; }
      rsync() { return 97; }
      operator_main
      test -f "${'$'}PWD/build/persist-called"
      test -f "${'$'}PWD/build/refresh-called"
      test -f "${'$'}PWD/build/archive-called"
      """
        .trimIndent()
    return run(
      listOf("/bin/bash", "-c", harness, "operator-main-harness", script.toString()),
      workspace,
    )
  }

  private fun runPublicSelection(
    fixture: ArchiveFixture,
    operatorTransform: (String) -> String = { it },
    committedAttemptMutation: ((Path) -> Unit)? = null,
    driverOverride: Path = fixture.driver,
  ): ProcessResult {
    val workspace =
      Files.createTempDirectory(temporaryDirectory, "public-selection-workspace-").toRealPath()
    assertProcessSucceeds(
      listOf(
        "/bin/bash",
        "-c",
        "source ${quote(operator)}; write_root_checksum_inventory ${quote(fixture.archive)}",
      )
    )
    assertProcessSucceeds(listOf("git", "init", "-q"), workspace)
    assertProcessSucceeds(
      listOf(
        "git",
        "fetch",
        "-q",
        "--depth=1",
        Path.of("").toAbsolutePath().toString(),
        IMPLEMENTATION_SHA,
      ),
      workspace,
    )
    assertProcessSucceeds(listOf("git", "checkout", "-q", "--detach", "FETCH_HEAD"), workspace)
    assertProcessSucceeds(listOf("git", "config", "user.name", "CS2a Test"), workspace)
    assertProcessSucceeds(
      listOf("git", "config", "user.email", "cs2a-test@example.invalid"),
      workspace,
    )
    write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
    val attempt =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/" + "cs2a-$IMPLEMENTATION_SHA/cs2a.SelectionFixture"
      )
    Files.createDirectories(attempt)
    assertProcessSucceeds(
      listOf("/bin/cp", "-a", "${fixture.archive}/.", attempt.toString()),
      workspace,
    )
    val relativeAttempt = workspace.relativize(attempt).toString()
    assertProcessSucceeds(listOf("git", "add", "-f", "--", relativeAttempt), workspace)
    assertProcessSucceeds(listOf("git", "commit", "-qm", "archive selection fixture"), workspace)
    val evidenceSha = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    committedAttemptMutation?.invoke(attempt)
    val operatorBundle = Files.createDirectories(workspace.resolve("operator-bundle"))
    listOf(controlledRunner, supervisor, manifestValidator).forEach { source ->
      Files.copy(
        source,
        operatorBundle.resolve(source.fileName),
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
    operatorBundle.resolve(manifestValidator.fileName).toFile().setExecutable(true, false)
    val productionOperator = Files.readString(operator)
    val productionPolicyPin =
      Regex("readonly EXPECTED_POLICY_SHA256=[0-9a-f]{64}").findAll(productionOperator).toList()
    assertThat(productionPolicyPin).hasSize(1)
    val testOperator = operatorBundle.resolve(operator.fileName)
    write(
      testOperator,
      operatorTransform(
        productionOperator.replace(
          productionPolicyPin.single().value,
          "readonly EXPECTED_POLICY_SHA256=${fixture.policySha256}",
        )
      ),
    )
    val harness =
      """
      source ${quote(testOperator)}
      prepare_operator_source() { AUTHENTICATED_SOURCE_ROOT=/tmp; }
      prepare_local_driver() { LOCAL_DRIVER=${quote(driverOverride)}; }
      operator_main --validate-attempt ${quote(attempt)} $IMPLEMENTATION_SHA $evidenceSha
      """
        .trimIndent()
    return run(listOf("/bin/bash", "-c", harness), workspace)
  }

  private fun runArchiveSemanticSeparation(script: String, name: String): ProcessResult {
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("archive-semantic-$name")).toRealPath()
    val bundle = Files.createDirectories(workspace.resolve("operator-bundle"))
    listOf(controlledRunner, supervisor, manifestValidator).forEach { source ->
      Files.copy(source, bundle.resolve(source.fileName), StandardCopyOption.REPLACE_EXISTING)
    }
    val testOperator = bundle.resolve(operator.fileName)
    write(testOperator, script)
    val remote = Files.createDirectories(workspace.resolve("remote"))
    listOf("manifests", "results", "logs", "meta").forEach { directory ->
      Files.createDirectories(remote.resolve(directory))
    }
    write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
    write(workspace.resolve("build/cs2a-supervisor.log"), "trusted supervisor log\n")
    write(workspace.resolve("build/cs2a-supervisor-exit.txt"), "70\n")
    val harness =
      """
      source "${'$'}1"
      REMOTE_FIXTURE=${'$'}2
      RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.SemanticSeparation123
      GOVERNOR_STATE=/run/revoman-cs2a/governor-state.SemanticSeparation123
      prepare_operator_source() { AUTHENTICATED_SOURCE_ROOT=/tmp; }
      verify_remote_bundle() { return 0; }
      validate_remote_final_handoff() { return 0; }
      ssh() {
        case "${'$'}*" in
          *readlink\ -f*) printf '%s\n' "${'$'}RUN_ROOT" ;;
          *run-root.txt*) printf '%s\n' "${'$'}RUN_ROOT" ;;
          *implementation-sha.txt*) printf '%s\n' "$IMPLEMENTATION_SHA" ;;
          *executed-script-sha256sums.tsv*)
            printf 'runner\t%s\nsupervisor\t%s\n' \
              "${'$'}(sha256_of "${'$'}CONTROLLED_RUNNER")" \
              "${'$'}(sha256_of "${'$'}SUPERVISOR")"
            ;;
          *operator-post-supervisor-exit.txt*) printf '%s\n' 70 ;;
          *) return 97 ;;
        esac
      }
      rsync() {
        local destination=${'$'}3 directory
        directory=${'$'}{destination%/}
        directory=${'$'}{directory##*/}
        cp -a "${'$'}REMOTE_FIXTURE/${'$'}directory/." "${'$'}destination/"
      }
      if operator_main --archive-only "${'$'}RUN_ROOT" "${'$'}GOVERNOR_STATE"; then
        exit 98
      else
        test "${'$'}?" -eq 70
      fi
      test -d "${'$'}EVIDENCE_ROOT/cs2a-${'$'}CS2A_IMPLEMENTATION_SHA/cs2a.SemanticSeparation123"
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "archive-semantic-harness",
        testOperator.toString(),
        remote.toString(),
      ),
      workspace,
    )
  }

  private fun runPersistSemanticSeparation(script: String, name: String): ProcessResult {
    val workspace = createPersistenceWorkspace("persist-semantic-$name")
    val implementation = run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim()
    val attempt =
      workspace.resolve(
        "docs/superpowers/benchmarks/results/v1/" + "cs2a-$implementation/operator-failure.semantic"
      )
    write(attempt.resolve("meta/operator-final-exit.txt"), "70\n")
    preparePersistOnly(workspace, attempt, implementation)
    val testOperator = workspace.resolve("build/operator-$name.sh")
    write(testOperator, script)
    return run(listOf("/bin/bash", testOperator.toString(), "--persist-only", "70"), workspace)
  }

  private fun runOperatorModeHarness(script: Path, mode: String): ProcessResult {
    val workspace =
      Files.createDirectories(temporaryDirectory.resolve("operator-mode-$mode-${script.fileName}"))
    write(workspace.resolve("build/cs2a-implementation-sha"), "$IMPLEMENTATION_SHA\n")
    val harness =
      """
      source "${'$'}1"
      MODE=${'$'}2
      prepare_operator_source() {
        : >"${'$'}PWD/build/preparation-called"
        AUTHENTICATED_SOURCE_ROOT=/tmp
      }
      prepare_local_driver() {
        LOCAL_DRIVER=/bin/false
      }
      persist_attempt() { printf 'persist:%s\n' "${'$'}*" >"${'$'}PWD/build/dispatch"; }
      validate_persisted_attempt() {
        printf 'validate:%s\n' "${'$'}*" >"${'$'}PWD/build/dispatch"
      }
      verify_remote_bundle() { return 0; }
      validate_remote_final_handoff() { return 0; }
      archive_remote_attempt() { printf 'archive:%s\n' "${'$'}*" >"${'$'}PWD/build/dispatch"; }
      ssh() { return 97; }
      rsync() { return 97; }
      case "${'$'}MODE" in
        persist)
          operator_main --persist-only 70
          test "${'$'}(cat "${'$'}PWD/build/dispatch")" = 'persist:70'
          test ! -e "${'$'}PWD/build/preparation-called"
          ;;
        validate)
          operator_main --validate-attempt \
            /tmp/operator-failure.fixture $IMPLEMENTATION_SHA ${"d".repeat(40)}
          test "${'$'}(cat "${'$'}PWD/build/dispatch")" = \
            'validate:/tmp/operator-failure.fixture $IMPLEMENTATION_SHA ${"d".repeat(40)} /bin/false'
          test -f "${'$'}PWD/build/preparation-called"
          ;;
        archive)
          operator_main --archive-only \
            /opt/revoman-benchmark/runs/cs2a.Mode123 \
            /run/revoman-cs2a/governor-state.Mode123
          test "${'$'}(cat "${'$'}PWD/build/dispatch")" = \
            'archive:/opt/revoman-benchmark/runs/cs2a.Mode123 /run/revoman-cs2a/governor-state.Mode123'
          test -f "${'$'}PWD/build/preparation-called"
          ;;
        *) exit 64 ;;
      esac
      """
        .trimIndent()
    return run(
      listOf(
        "/bin/bash",
        "-c",
        harness,
        "operator-mode-harness",
        script.toString(),
        mode,
      ),
      workspace,
    )
  }

  private fun assertRefreshCallSite(script: Path, expectedSuccess: Boolean) {
    val marker = temporaryDirectory.resolve("${script.fileName}.refresh-called")
    val harness =
      """
      source "${'$'}1"
      REFRESH_MARKER=${'$'}2
      ssh() {
        case "${'$'}*" in
          *--publish-final-handoff*) : >"${'$'}REFRESH_MARKER" ;;
        esac
      }
      refresh_remote_final_handoff \
        /opt/revoman-benchmark/runs/cs2a.CallSite123 \
        /run/revoman-cs2a/governor-state.CallSite123
      test -f "${'$'}REFRESH_MARKER"
      """
        .trimIndent()
    val result =
      run(
        listOf(
          "/bin/bash",
          "-c",
          harness,
          "refresh-harness",
          script.toString(),
          marker.toString(),
        )
      )
    if (expectedSuccess) {
      assertWithMessage(result.output).that(result.exitCode).isEqualTo(0)
    } else {
      assertWithMessage(result.output).that(result.exitCode).isNotEqualTo(0)
    }
  }

  private fun assertBashFunctionFails(invocation: String, label: String) {
    val result =
      run(
        listOf("/bin/bash", "-c", "source ${quote(operator)}; $invocation"),
        Path.of("").toAbsolutePath(),
      )
    assertWithMessage("$label\n${result.output}").that(result.exitCode).isNotEqualTo(0)
  }

  private fun runPublicationToolDiscoveryProbe(name: String, moveBehavior: String): ProcessResult {
    val fakeBin = Files.createDirectories(temporaryDirectory.resolve("publication-probe-$name"))
    val realStat =
      run(listOf("/bin/bash", "-c", "command -v gstat || command -v stat")).output.trim()
    val fakeMove =
      """
      #!/bin/sh
      if test "${'$'}1" = --version; then
        printf '%s\n' 'mv (GNU coreutils) 9.99'
        exit 0
      fi
      """
        .trimIndent() + "\n" + moveBehavior.trimIndent() + "\n"
    val fakeStat =
      """
      #!/bin/sh
      if test "${'$'}1" = --version; then
        printf '%s\n' 'stat (GNU coreutils) 9.99'
      else
        exec ${quote(Path.of(realStat))} "${'$'}@"
      fi
      """
        .trimIndent() + "\n"
    listOf("mv", "gmv").forEach { tool ->
      write(fakeBin.resolve(tool), fakeMove)
      fakeBin.resolve(tool).toFile().setExecutable(true, false)
    }
    listOf("stat", "gstat").forEach { tool ->
      write(fakeBin.resolve(tool), fakeStat)
      fakeBin.resolve(tool).toFile().setExecutable(true, false)
    }
    val harness =
      """
      PATH=${quote(fakeBin)}:${'$'}PATH
      export PATH
      source ${quote(operator)}
      discover_publication_tools
      """
        .trimIndent()

    return run(listOf("/bin/bash", "-c", harness))
  }

  private fun assertSupervisorFunctionSucceeds(invocation: String) {
    assertProcessSucceeds(listOf("/bin/bash", "-c", "source ${quote(supervisor)}; $invocation"))
  }

  private fun assertSupervisorFunctionFails(invocation: String, label: String) {
    val result = run(listOf("/bin/bash", "-c", "source ${quote(supervisor)}; $invocation"))
    assertWithMessage("$label\n${result.output}").that(result.exitCode).isNotEqualTo(0)
  }

  private fun assertProcessSucceeds(
    command: List<String>,
    workingDirectory: Path = Path.of("").toAbsolutePath(),
  ) {
    val result = run(command, workingDirectory)
    assertWithMessage("%s", "command=${command.joinToString(" ")}\n${result.output}")
      .that(result.exitCode)
      .isEqualTo(0)
  }

  private fun run(
    command: List<String>,
    workingDirectory: Path = Path.of("").toAbsolutePath(),
  ): ProcessResult {
    val process =
      ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return ProcessResult(process.waitFor(), output)
  }

  private fun write(path: Path, content: String) {
    Files.createDirectories(path.parent)
    Files.writeString(path, content)
  }

  private fun mutateJson(path: Path, filter: String) {
    val candidate = path.resolveSibling(".${path.fileName}.mutation")
    val result = run(listOf("jq", filter, path.toString()), path.parent)
    assertWithMessage(result.output).that(result.exitCode).isEqualTo(0)
    write(candidate, result.output)
    Files.move(candidate, path, StandardCopyOption.REPLACE_EXISTING)
  }

  private fun createPersistenceWorkspace(name: String, extraIgnore: String = ""): Path {
    val workspace = Files.createDirectories(temporaryDirectory.resolve(name)).toRealPath()
    assertProcessSucceeds(listOf("git", "init", "-q"), workspace)
    assertProcessSucceeds(listOf("git", "config", "user.name", "CS2a Test"), workspace)
    assertProcessSucceeds(
      listOf("git", "config", "user.email", "cs2a-test@example.invalid"),
      workspace,
    )
    write(workspace.resolve(".gitignore"), "build/\n$extraIgnore")
    assertProcessSucceeds(listOf("git", "add", ".gitignore"), workspace)
    assertProcessSucceeds(listOf("git", "commit", "-qm", "implementation"), workspace)
    return workspace
  }

  private fun preparePersistOnly(workspace: Path, attempt: Path, implementation: String) {
    write(workspace.resolve("build/cs2a-implementation-sha"), "$implementation\n")
    write(workspace.resolve("build/cs2a-operator-status.txt"), "70\n")
    write(workspace.resolve("build/cs2a-local-evidence-dir.txt"), "$attempt\n")
    assertProcessSucceeds(
      listOf(
        "/bin/bash",
        "-c",
        "source ${quote(operator)}; write_root_checksum_inventory ${quote(attempt)}",
      ),
      workspace,
    )
  }

  private fun assertPersistOnlyFailsBeforeCommit(
    workspace: Path,
    attempt: Path,
    implementation: String,
  ) {
    write(workspace.resolve("build/cs2a-implementation-sha"), "$implementation\n")
    write(workspace.resolve("build/cs2a-operator-status.txt"), "70\n")
    write(workspace.resolve("build/cs2a-local-evidence-dir.txt"), "$attempt\n")
    assertProcessSucceeds(
      listOf(
        "/bin/bash",
        "-c",
        "source ${quote(operator)}; write_root_checksum_inventory ${quote(attempt)}",
      ),
      workspace,
    )

    val result = run(listOf("/bin/bash", operator.toString(), "--persist-only", "70"), workspace)

    assertWithMessage(result.output).that(result.exitCode).isNotEqualTo(0)
    assertThat(run(listOf("git", "rev-parse", "HEAD"), workspace).output.trim())
      .isEqualTo(implementation)
    assertThat(run(listOf("git", "diff", "--cached", "--name-only"), workspace).output).isEmpty()
    assertThat(run(listOf("git", "ls-files", "--", attempt.toString()), workspace).output).isEmpty()
    assertThat(Files.exists(workspace.resolve("build/cs2a-attempt-evidence-sha.txt"))).isFalse()
  }

  private fun createStageFixture(name: String, stage: String): Path {
    val archive = Files.createDirectories(temporaryDirectory.resolve(name))
    Files.createDirectories(archive.resolve("manifests"))
    Files.createDirectories(archive.resolve("results"))
    Files.createDirectories(archive.resolve("logs"))
    Files.createDirectories(archive.resolve("meta"))
    write(archive.resolve("meta/stage.txt"), "$stage\n")
    write(archive.resolve("meta/run-root.txt"), "$RUN_ROOT\n")
    val commandCount =
      when (stage) {
        "setup" -> 0
        "aa-captured" -> 9
        "aa-compared" -> 13
        "candidate-captured" -> 16
        "candidate-compared" -> 22
        else -> 0
      }
    val commandRows = expectedCommandRows(archive).take(commandCount)
    write(
      archive.resolve("meta/commands.tsv"),
      commandRows.joinToString("\n", postfix = if (commandRows.isEmpty()) "" else "\n"),
    )
    if (stage != "setup") {
      listOf("baseline-a.json", "baseline-b.json", "candidate.json").forEach { file ->
        write(archive.resolve("manifests/$file"), "{}\n")
      }
    }
    expectedResults(stage).forEach { file -> write(archive.resolve("results/$file"), "{}\n") }
    expectedCaptureExits(stage).forEach { file -> write(archive.resolve("meta/$file"), "0\n") }
    expectedComparisonExits(stage).forEach { file -> write(archive.resolve("meta/$file"), "0\n") }
    return archive
  }

  private fun expectedCommandRows(archive: Path): List<String> {
    val destination = archive.resolve("meta/.expected-commands.tsv")
    assertProcessSucceeds(
      listOf(
        "/bin/bash",
        "-c",
        "source ${quote(operator)}; " +
          "write_expected_command_protocol ${quote(archive)} ${quote(destination)}",
      )
    )
    val rows = Files.readAllLines(destination)
    Files.delete(destination)
    return rows
  }

  private fun createRemoteByteFixture(name: String): Path {
    val archive = Files.createDirectories(temporaryDirectory.resolve(name))
    val files =
      linkedMapOf(
        "manifests/baseline-a.json" to "manifest\n",
        "results/cold-aa.json" to "result\n",
        "logs/capture.stdout" to "stdout\n",
        "logs/capture.stderr" to "stderr\n",
        "logs/capture.exit" to "0\n",
        "meta/stage.txt" to "aa-captured\n",
      )
    files.forEach { (path, content) -> write(archive.resolve(path), content) }
    val inventory =
      files.keys
        .sorted()
        .joinToString(
          separator = "",
          transform = { path ->
            "${sha256(archive.resolve(path))}  $path\n"
          },
        )
    write(archive.resolve("meta/remote-byte-sha256sums.txt"), inventory)
    return archive
  }

  private fun createCompleteArchiveFixture(name: String): ArchiveFixture {
    val archive = createStageFixture(name, "candidate-compared")
    val manifests =
      linkedMapOf(
        "baseline-a.json" to targetManifest("baseline-a-cs2a", BASELINE_SHA),
        "baseline-b.json" to targetManifest("baseline-b-cs2a", BASELINE_SHA),
        "candidate.json" to targetManifest("candidate-cs2a", IMPLEMENTATION_SHA),
      )
    manifests.forEach { (file, content) -> write(archive.resolve("manifests/$file"), content) }
    write(
      archive.resolve("results/cold-aa.json"),
      campaign(archive, "COLD", "baseline-b-cs2a", "baseline-83f3cd70", BASELINE_SHA),
    )
    write(
      archive.resolve("results/warm-aa.json"),
      campaign(archive, "WARM", "baseline-b-cs2a", "baseline-83f3cd70", BASELINE_SHA),
    )
    listOf("cold" to "COLD", "warm" to "WARM", "retained" to "RETAINED").forEach {
      (fileMode, jsonMode) ->
      write(
        archive.resolve("results/$fileMode-candidate.json"),
        campaign(archive, jsonMode, "candidate-cs2a", "major-v1", IMPLEMENTATION_SHA),
      )
    }
    expectedResults("candidate-compared")
      .filter { it.startsWith("comparison-") && it.endsWith(".json") }
      .forEach { file -> write(archive.resolve("results/$file"), PASS_JSON) }
    expectedResults("candidate-compared")
      .filter { it.startsWith("comparison-") && it.endsWith(".md") }
      .forEach { file -> write(archive.resolve("results/$file"), PASS_MARKDOWN) }

    listOf(controlledRunner, supervisor, operator, manifestValidator).forEach { source ->
      Files.copy(
        source,
        archive.resolve("meta/${source.fileName}"),
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
    val policy = archive.resolve("meta/controlled-host.json")
    write(
      policy,
      "{\"schema\":\"fixture-controlled-host/v1\",\"maximumReplacementBlocks\":2}\n",
    )
    val policySha = sha256(policy)
    write(archive.resolve("meta/implementation-sha.txt"), "$IMPLEMENTATION_SHA\n")
    write(archive.resolve("meta/controlled-uid.txt"), "1234\n")
    write(
      archive.resolve("meta/policy-sha256.txt"),
      "$policySha  /opt/revoman-benchmark/controlled-host.json\n",
    )
    write(archive.resolve("meta/policy-semantic-sha256.txt"), "$POLICY_SEMANTIC_SHA\n")
    write(archive.resolve("meta/run-root.txt"), "$RUN_ROOT\n")
    val commandRows = expectedCommandRows(archive)
    write(archive.resolve("meta/commands.tsv"), commandRows.joinToString("\n", postfix = "\n"))
    commandRows.forEach { row ->
      val label = row.substringBefore('\t')
      write(archive.resolve("logs/$label.stdout"), "$label\n")
      write(archive.resolve("logs/$label.stderr"), "")
      write(archive.resolve("logs/$label.exit"), "0\n")
    }
    write(archive.resolve("meta/runner-exit.txt"), "0\n")
    write(archive.resolve("meta/inventory-exit.txt"), "0\n")
    writeArtifactInventoriesFromResults(archive)
    refreshRemoteEvidenceInventory(archive)
    refreshCommandOutputInventory(archive)
    writeOperatorScriptInventory(archive)
    refreshRemoteByteInventory(archive)

    val supervisorMeta = Files.createDirectories(archive.resolve("meta/supervisor"))
    val runnerSha = sha256(archive.resolve("meta/cs2a-controlled-run.sh"))
    val supervisorSha = sha256(archive.resolve("meta/cs2a-governor-supervisor.sh"))
    val executedRows = "runner\t$runnerSha\nsupervisor\t$supervisorSha\n"
    write(supervisorMeta.resolve("child-or-supervisor-status.txt"), "0\n")
    write(supervisorMeta.resolve("restoration-failed.txt"), "false\n")
    write(supervisorMeta.resolve("containment-failed.txt"), "false\n")
    write(supervisorMeta.resolve("finished-at.txt"), "2026-08-13T00:00:00Z\n")
    write(supervisorMeta.resolve("original-governors.tsv"), "/sys/cpu0\tpowersave\n")
    write(supervisorMeta.resolve("restored-governors.tsv"), "/sys/cpu0\tpowersave\n")
    write(supervisorMeta.resolve("run-root.txt"), "$RUN_ROOT\n")
    write(supervisorMeta.resolve("implementation-sha.txt"), "$IMPLEMENTATION_SHA\n")
    write(supervisorMeta.resolve("operator-post-supervisor-exit.txt"), "0\n")
    write(supervisorMeta.resolve("lock-released.txt"), "true\n")
    write(supervisorMeta.resolve("lock-provenance.txt"), "0:0:600:1:2\n")
    write(supervisorMeta.resolve("executed-script-sha256sums.tsv"), executedRows)
    write(
      supervisorMeta.resolve("authenticated-handoff.tsv"),
      "implementation\t$IMPLEMENTATION_SHA\nuid\t1234\n$executedRows",
    )
    val supervisorCore = Files.createDirectories(archive.resolve("meta/supervisor-core"))
    CORE_SUPERVISOR_FILES.forEach { name ->
      Files.copy(supervisorMeta.resolve(name), supervisorCore.resolve(name))
    }
    write(archive.resolve("meta/operator-supervisor.log"), "RUN_ROOT=$RUN_ROOT\n")
    write(archive.resolve("meta/operator-supervisor-exit.txt"), "0\n")
    write(archive.resolve("meta/operator-post-supervisor-exit.txt"), "0\n")
    write(archive.resolve("meta/operator-resume-validation-exit.txt"), "0\n")
    write(archive.resolve("meta/local-validation-passed.txt"), "false\n")
    write(archive.resolve("meta/operator-final-exit.txt"), "0\n")

    val driver = temporaryDirectory.resolve("$name-driver.sh")
    write(
      driver,
      """
      #!/usr/bin/env bash
      set -e
      case "${'$'}1" in
        verify) exit 0 ;;
        compare)
          shift
          while test "${'$'}#" -gt 0; do
            case "${'$'}1" in
              --output-json) output_json=${'$'}2; shift 2 ;;
              --output-md) output_md=${'$'}2; shift 2 ;;
              *) shift ;;
            esac
          done
          printf '%s\n' '{"overall":"PASS"}' >"${'$'}output_json"
          printf '%s\n' 'PASS' >"${'$'}output_md"
          ;;
        *) exit 2 ;;
      esac
      """
        .trimIndent() + "\n",
    )
    driver.toFile().setExecutable(true, false)
    return ArchiveFixture(archive, driver, policySha)
  }

  private fun targetManifest(targetId: String, commit: String): String {
    val tree = if (commit == BASELINE_SHA) BASELINE_TREE else IMPLEMENTATION_TREE
    return """
    {
      "schema":"revoman-target-manifest/v1",
      "targetId":"$targetId",
      "gitCommit":"$commit",
      "gitTree":"$tree",
      "dirty":false,
      "gradleVersion":"9.7.0",
      "wrapperSha256":"${"c".repeat(64)}",
      "jdk":{
        "distribution":"fixture",
        "vendor":"fixture",
        "fullVersion":"21.0.10+7",
        "javaHome":"/remote/jdk",
        "jvmFlags":["-Xms256m"]
      },
      "classpath":[{
        "logicalId":"revoman-root",
        "executionPath":"/remote/revoman.jar",
        "sizeBytes":123,
        "sha256":"${"d".repeat(64)}"
      }]
    }
    """
      .trimIndent() + "\n"
  }

  private fun campaign(
    archive: Path,
    mode: String,
    candidateId: String,
    candidateAdapter: String,
    candidateCommit: String,
  ): String {
    val baselineHash = sha256(archive.resolve("manifests/baseline-a.json"))
    val candidateManifest =
      if (candidateId == "baseline-b-cs2a") "baseline-b.json" else "candidate.json"
    val candidateHash = sha256(archive.resolve("manifests/$candidateManifest"))
    val blocks = if (mode == "COLD") 50 else 5
    val warmups = if (mode == "WARM") 20 else 0
    val iterations =
      when (mode) {
        "COLD" -> 1
        "WARM" -> 100
        else -> 0
      }
    val metricPasses =
      when (mode) {
        "COLD" -> "[\"LATENCY\",\"ALLOCATION\",\"PEAK_RSS\"]"
        "WARM" -> "[\"LATENCY\",\"ALLOCATION\"]"
        else -> "[\"RETAINED\"]"
      }
    val series = metricSeries(mode, candidateId, blocks)
    return """
      {
        "campaignId":"fixture-$mode-$candidateId",
        "schema":"revoman-benchmark/v1",
        "intent":"CONTROLLED",
        "createdAt":"2026-08-13T00:00:00Z",
        "configuration":{
          "mode":"$mode",
          "metricPasses":$metricPasses,
          "seed":5928239383101656625,
          "requestedAcceptedBlocks":$blocks,
          "forksPerBlock":1,
          "warmupIterations":$warmups,
          "measurementIterations":$iterations,
          "targets":[
            {"role":"BASELINE","targetId":"baseline-a-cs2a","adapterId":"baseline-83f3cd70"},
            {"role":"CANDIDATE","targetId":"$candidateId","adapterId":"$candidateAdapter"}
          ]
        },
        "harness":{
          "commit":"$IMPLEMENTATION_SHA",
          "tree":"$IMPLEMENTATION_TREE",
          "dirty":false,
          "distributionSha256":"${"1".repeat(64)}",
          "artifacts":[{
            "logicalId":"benchmark-driver.jar",
            "executionPath":"lib/benchmark-driver.jar",
            "sizeBytes":123,
            "sha256":"${"2".repeat(64)}"
          }],
          "workloadContractSha256":"$WORKLOAD_CONTRACT_SHA",
          "fixtureSetSha256":"${"3".repeat(64)}",
          "adapters":[
            {"id":"baseline-83f3cd70","sourceSha256":"${"4".repeat(64)}"},
            {"id":"major-v1","sourceSha256":"${"5".repeat(64)}"}
          ]
        },
        "environment":{
          "policySha256":"$POLICY_SEMANTIC_SHA",
          "hostFingerprintSha256":"$HOST_FINGERPRINT",
          "governor":"performance",
          "osName":"Linux",
          "osVersion":"fixture",
          "kernel":"fixture",
          "cpuModel":"fixture",
          "cpuCount":8,
          "physicalMemoryBytes":123456789,
          "jdk":{
            "distribution":"fixture",
            "vendor":"fixture",
            "fullVersion":"21.0.10+7",
            "javaHome":"$RUNNER_JAVA_HOME",
            "jvmFlags":["-Dsun.net.httpserver.nodelay=true"]
          }
        },
        "targets":[
          {
            "id":"baseline-a-cs2a",
            "gitCommit":"$BASELINE_SHA",
            "gitTree":"$BASELINE_TREE",
            "dirty":false,
            "gradleVersion":"9.7.0",
            "wrapperSha256":"${"c".repeat(64)}",
            "buildJdk":$TARGET_JDK,
            "manifestSha256":"$baselineHash",
            "classpathSha256":"${"6".repeat(64)}",
            "classpath":[{"logicalId":"revoman-root","sizeBytes":123,"sha256":"${"d".repeat(64)}"}],
            "adapter":{"id":"baseline-83f3cd70","sourceSha256":"${"4".repeat(64)}"}
          },
          {
            "id":"$candidateId",
            "gitCommit":"$candidateCommit",
            "gitTree":"${if (candidateCommit == BASELINE_SHA) BASELINE_TREE else IMPLEMENTATION_TREE}",
            "dirty":false,
            "gradleVersion":"9.7.0",
            "wrapperSha256":"${"c".repeat(64)}",
            "buildJdk":$TARGET_JDK,
            "manifestSha256":"$candidateHash",
            "classpathSha256":"${"6".repeat(64)}",
            "classpath":[{"logicalId":"revoman-root","sizeBytes":123,"sha256":"${"d".repeat(64)}"}],
            "adapter":{"id":"$candidateAdapter","sourceSha256":"${if (candidateAdapter == "major-v1") "5".repeat(64) else "4".repeat(64)}"}
          }
        ],
        "workloads":[{
          "id":"lifecycle.no-script-one-step.v1",
          "contractSha256":"$WORKLOAD_CONTRACT_SHA",
          "fixtureSha256":"$WORKLOAD_FIXTURE_SHA",
          "mode":"$mode",
          "metricSeries":$series
        }]
      }
      """
      .trimIndent() + "\n"
  }

  private fun metricSeries(mode: String, candidateId: String, blockCount: Int): String {
    val blocks = campaignBlocks(candidateId, blockCount)
    val artifactSet =
      if (candidateId == "baseline-b-cs2a") mode.lowercase() + "-aa"
      else mode.lowercase() + "-candidate"
    return when (mode) {
      "COLD" ->
        """[
          ${metricSeriesJson("LATENCY", "parent-process-wall-time/v1", "NANOSECONDS", "[]", blocks)},
          ${metricSeriesJson("ALLOCATED_BYTES", "jdk21-jfr-tlab-reserved-plus-outside/v1", "BYTES", coldArtifacts(artifactSet, blockCount), blocks)},
          ${metricSeriesJson("PEAK_RSS", "gnu-time-v-maximum-resident-set-kib/v1", "BYTES", "[]", blocks)}
        ]"""
          .trimIndent()
      "WARM" ->
        """[
          ${metricSeriesJson("LATENCY", "target-nano-time/v1", "NANOSECONDS", "[]", blocks)},
          ${metricSeriesJson("ALLOCATED_BYTES", "jmh:gc.alloc.rate.norm:com.salesforce.revoman.benchmark.WarmLifecycleAllocationBenchmark.execute", "BYTES_PER_OPERATION", warmArtifacts(artifactSet, blockCount), blocks)}
        ]"""
          .trimIndent()
      else ->
        "[${metricSeriesJson("RETAINED_BYTES", "revoman-retained-two-phase-weak-proof-final-heap/v2", "BYTES", "[]", blocks)}]"
    }
  }

  private fun metricSeriesJson(
    metric: String,
    provider: String,
    unit: String,
    artifacts: String,
    blocks: String,
  ): String =
    """{"metric":"$metric","provider":"$provider","providerConfigurationSha256":"${"7".repeat(64)}","unit":"$unit","artifacts":$artifacts,"blocks":$blocks,"histograms":null}"""

  private fun campaignBlocks(candidateId: String, count: Int): String =
    (0 until count).joinToString(prefix = "[", postfix = "]") { block ->
      val order =
        if (block % 2 == 0) "[\"baseline-a-cs2a\",\"$candidateId\"]"
        else "[\"$candidateId\",\"baseline-a-cs2a\"]"
      """{"blockId":$block,"targetOrder":$order,"accepted":true,"rejectionReasons":[],"observations":[{"fork":0}]}"""
    }

  private fun coldArtifacts(artifactSet: String, blocks: Int): String =
    (0 until blocks)
      .flatMap { block -> listOf("baseline", "candidate").map { block to it } }
      .joinToString(prefix = "[", postfix = "]") { (block, role) ->
        val logical = "cold-allocation-block-$block-role-$role-fork-0.jfr"
        artifactJson(
          logical,
          "$RUN_ROOT/artifacts/$artifactSet/pass-allocation/block-$block/role-$role/fork-0/$logical",
        )
      }

  private fun warmArtifacts(artifactSet: String, blocks: Int): String =
    (0 until blocks)
      .flatMap { block ->
        listOf("baseline", "candidate").flatMap { role ->
          listOf("raw.json", "normalized.json", "output.txt").map { suffix ->
            Triple(block, role, suffix)
          }
        }
      }
      .joinToString(prefix = "[", postfix = "]") { (block, role, suffix) ->
        val logical = "warm-allocation-block-$block-role-$role-fork-0-$suffix"
        val actual =
          when (suffix) {
            "raw.json" -> "jmh-raw.json"
            "normalized.json" -> "revoman-benchmark-jmh-v1.json"
            else -> "jmh-output.txt"
          }
        artifactJson(
          logical,
          "$RUN_ROOT/artifacts/$artifactSet/pass-allocation/block-$block/role-$role/fork-0/$actual",
        )
      }

  private fun artifactJson(logicalId: String, executionPath: String): String =
    """{"logicalId":"$logicalId","executionPath":"$executionPath","sizeBytes":123,"sha256":"${"a".repeat(64)}"}"""

  private fun refreshRemoteEvidenceInventory(archive: Path) {
    val paths =
      (Files.list(archive.resolve("manifests")).use { it.toList() } +
          Files.list(archive.resolve("results")).use { it.toList() })
        .filter { Files.isRegularFile(it) }
        .map { archive.relativize(it).toString() }
        .sorted()
    writeShaInventory(archive, "meta/evidence-sha256sums.txt", paths)
  }

  private fun writeArtifactInventoriesFromResults(archive: Path) {
    val artifacts = linkedMapOf<String, Pair<Long, String>>()
    listOf(
        "cold-aa.json",
        "warm-aa.json",
        "cold-candidate.json",
        "warm-candidate.json",
        "retained-candidate.json",
      )
      .forEach { resultName ->
        val result = archive.resolve("results/$resultName")
        val query =
          run(
            listOf(
              "jq",
              "-r",
              ".workloads[].metricSeries[].artifacts[] | " +
                "[.executionPath, (.sizeBytes|tostring), .sha256] | @tsv",
              result.toString(),
            )
          )
        assertWithMessage(query.output).that(query.exitCode).isEqualTo(0)
        query.output
          .lineSequence()
          .filter { it.isNotBlank() }
          .forEach { row ->
            val fields = row.split('\t')
            val relative = fields[0].removePrefix("$RUN_ROOT/")
            artifacts[relative] = fields[1].toLong() to fields[2]
            if (resultName.startsWith("warm-")) {
              val parent = relative.substringBeforeLast('/')
              artifacts.putIfAbsent(
                "$parent/target-verification-token.json",
                64L to "${"b".repeat(64)}",
              )
              artifacts.putIfAbsent("$parent/campaign-jmh-context.json", 64L to "${"c".repeat(64)}")
            }
          }
      }
    val ordered = artifacts.toSortedMap()
    write(
      archive.resolve("meta/artifact-inventory.tsv"),
      ordered.entries.joinToString("") { (path, identity) -> "$path\t${identity.first}\n" },
    )
    write(
      archive.resolve("meta/artifact-sha256sums.txt"),
      ordered.entries.joinToString("") { (path, identity) -> "${identity.second}  $path\n" },
    )
  }

  private fun refreshCommandOutputInventory(archive: Path) {
    val paths =
      Files.list(archive.resolve("logs")).use { stream ->
        stream
          .filter { Files.isRegularFile(it) }
          .map { archive.relativize(it).toString() }
          .sorted()
          .toList()
      }
    writeShaInventory(archive, "meta/command-output-sha256sums.txt", paths)
  }

  private fun writeOperatorScriptInventory(archive: Path) {
    val paths =
      listOf(
        "meta/cs2a-controlled-run.sh",
        "meta/cs2a-governor-supervisor.sh",
        "meta/cs2a-operator.sh",
        "meta/cs2a-validate-manifest.jq",
      )
    val rows =
      paths.joinToString(separator = "") { path ->
        "${sha256(archive.resolve(path))}  ${path.removePrefix("meta/")}\n"
      }
    write(archive.resolve("meta/operator-script-sha256sums.txt"), rows)
  }

  private fun refreshRemoteByteInventory(archive: Path) {
    val localOnly =
      setOf(
        "meta/operator-supervisor.log",
        "meta/operator-supervisor-exit.txt",
        "meta/operator-post-supervisor-exit.txt",
        "meta/operator-resume-validation-exit.txt",
        "meta/operator-final-exit.txt",
        "meta/local-validation-passed.txt",
      )
    val paths =
      listOf("manifests", "results", "logs", "meta")
        .flatMap { directory ->
          Files.walk(archive.resolve(directory)).use { stream ->
            stream
              .filter { Files.isRegularFile(it) }
              .map { archive.relativize(it).toString() }
              .toList()
          }
        }
        .filterNot {
          it == "meta/remote-byte-sha256sums.txt" ||
            it.startsWith("meta/supervisor/") ||
            it.startsWith("meta/supervisor-core/") ||
            it in localOnly
        }
        .sorted()
    writeShaInventory(archive, "meta/remote-byte-sha256sums.txt", paths)
  }

  private fun writeShaInventory(archive: Path, inventory: String, paths: List<String>) {
    val rows =
      paths.joinToString(separator = "") { path ->
        "${sha256(archive.resolve(path))}  $path\n"
      }
    write(archive.resolve(inventory), rows)
  }

  private fun expectedResults(stage: String): List<String> = buildList {
    if (stage != "setup") addAll(listOf("cold-aa.json", "warm-aa.json"))
    if (stage in listOf("aa-compared", "candidate-captured", "candidate-compared")) {
      addAll(
        listOf(
          "comparison-aa-cold.json",
          "comparison-aa-cold.md",
          "comparison-aa-warm.json",
          "comparison-aa-warm.md",
        )
      )
    }
    if (stage in listOf("candidate-captured", "candidate-compared")) {
      addAll(listOf("cold-candidate.json", "warm-candidate.json", "retained-candidate.json"))
    }
    if (stage == "candidate-compared") {
      listOf("cold", "warm", "retained").forEach { mode ->
        add("comparison-candidate-$mode.json")
        add("comparison-candidate-$mode.md")
      }
    }
  }

  private fun expectedComparisonExits(stage: String): List<String> = buildList {
    if (stage in listOf("aa-compared", "candidate-captured", "candidate-compared")) {
      addAll(listOf("comparison-aa-cold-exit.txt", "comparison-aa-warm-exit.txt"))
    }
    if (stage == "candidate-compared") {
      addAll(
        listOf(
          "comparison-candidate-cold-exit.txt",
          "comparison-candidate-warm-exit.txt",
          "comparison-candidate-retained-exit.txt",
        )
      )
    }
  }

  private fun expectedCaptureExits(stage: String): List<String> = buildList {
    if (stage != "setup") addAll(listOf("cold-aa-exit.txt", "warm-aa-exit.txt"))
    if (stage in listOf("candidate-captured", "candidate-compared")) {
      addAll(
        listOf(
          "cold-candidate-exit.txt",
          "warm-candidate-exit.txt",
          "retained-candidate-exit.txt",
        )
      )
    }
  }

  private fun sha256(path: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {
      "%02x".format(it)
    }

  private fun quote(path: Path): String = "'${path.toString().replace("'", "'\\''")}'"

  private val operatorDirectory: Path =
    Path.of("docs/superpowers/benchmarks/operators").toAbsolutePath().normalize()
  private val controlledRunner = operatorDirectory.resolve("cs2a-controlled-run.sh")
  private val supervisor = operatorDirectory.resolve("cs2a-governor-supervisor.sh")
  private val operator = operatorDirectory.resolve("cs2a-operator.sh")
  private val manifestValidator = operatorDirectory.resolve("cs2a-validate-manifest.jq")
  private val shellScripts = listOf(controlledRunner, supervisor, operator)
  private val sourceBundle =
    linkedMapOf(
      "runner" to Files.readString(controlledRunner),
      "supervisor" to Files.readString(supervisor),
      "operator" to Files.readString(operator),
    )
  private val publicationToolPrelude =
    """
    case ${'$'}(uname -s) in
      Darwin)
        MV_COMMAND=${'$'}(command -v gmv) || exit 69
        STAT_COMMAND=${'$'}(command -v gstat) || exit 69
        ;;
      Linux)
        MV_COMMAND=${'$'}(command -v mv) || exit 69
        STAT_COMMAND=${'$'}(command -v stat) || exit 69
        ;;
      *) exit 69 ;;
    esac
    """
      .trimIndent()

  private data class ProcessResult(val exitCode: Int, val output: String)

  private data class ArchiveFixture(val archive: Path, val driver: Path, val policySha256: String)

  private companion object {
    const val TARGET_JDK =
      """{"distribution":"fixture","vendor":"fixture","fullVersion":"21.0.10+7","javaHome":"/remote/jdk","jvmFlags":["-Xms256m"]}"""
    const val BASELINE_SHA = "83f3cd70f78ad733412d10cbc8287aaabafe7aac"
    const val IMPLEMENTATION_SHA = "5bc96660edc3da6be9f36671676aafa3055c3548"
    const val IMPLEMENTATION_TREE = "f66bedc0367b8eac37ff817dcdc90bb22438d9ab"
    const val BASELINE_TREE = "e86b600e63f071119c6dd7ba3e06f69ac9cc5539"
    const val WORKLOAD_FIXTURE_SHA =
      "31af0229163ef1ed544189f9b1f1dbd9a80607ffd024a2e5bd09cddfae919c92"
    const val WORKLOAD_CONTRACT_SHA =
      "8b0f8eae2fd8849d68d0d0652df28fc66de93d3e449179bdd6efff39ad6cbcf1"
    const val RUNNER_JAVA_HOME =
      "/home/gopala.akshintala/core-public/tools/Linux/jdk/sfdc-jdk-zulu-21.helium_x64"
    const val POLICY_SEMANTIC_SHA =
      "48de27c7c84faec59c0ab2276489460ac4ffe3935cd0be41d9730b5aff1a3f60"
    const val HOST_FINGERPRINT = "12e7d565978e40259c2f4c956c9e05696a32c0ba574c6971dfe85c8acd69fe44"
    const val RUN_ROOT = "/opt/revoman-benchmark/runs/cs2a.Fixture123"
    const val PASS_JSON = "{\"overall\":\"PASS\"}\n"
    const val PASS_MARKDOWN = "PASS\n"

    val ARCHIVE_STAGES =
      listOf("setup", "aa-captured", "aa-compared", "candidate-captured", "candidate-compared")

    val OPERATOR_WITNESS_MUTATIONS =
      listOf(
        Triple("runner receives minimal authenticated UID", "runner", "CS2A_AUTHENTICATED_UID"),
        Triple(
          "runner must verify inherited lock descriptor",
          "runner",
          "readlink \"/proc/\$\$/fd/",
        ),
        Triple("runner must use exact jq validator", "runner", "jq -e -f"),
        Triple("supervisor must take exclusive lock", "supervisor", "flock -n 9"),
        Triple("supervisor must contain timeout", "supervisor", "--kill-after="),
        Triple(
          "supervisor must reject marker ambiguity",
          "supervisor",
          "run_root=\$(extract_run_root_marker",
        ),
        Triple(
          "supervisor must restore governors",
          "supervisor",
          "restore_governors \"\$STATE/original-governors.tsv\"",
        ),
        Triple(
          "supervisor must privileged-copy final state",
          "supervisor",
          "copy_final_state_to_run_root",
        ),
        Triple(
          "UID policy must be explicitly provisioned",
          "supervisor",
          "controlled_uid_policy_is_provisioned",
        ),
        Triple(
          "operator must privileged-read installed SHA",
          "operator",
          "dzdo cat /opt/revoman-benchmark/cs2a-implementation-sha",
        ),
        Triple(
          "operator status must use atomic hardlink no-clobber",
          "operator",
          "ln \\\"\\${'$'}candidate\\\" \\\"\\${'$'}destination\\\"",
        ),
        Triple(
          "checksum excludes only root manifest",
          "operator",
          "! -path './evidence-sha256sums.txt'",
        ),
        Triple("publication must use hidden sibling", "operator", ".cs2a-archive-stage.XXXXXXXX"),
        Triple(
          "publication must recover marker",
          "operator",
          "recover_publication_marker \"\$canonical\" \"\$marker\"",
        ),
        Triple(
          "publication must separate semantic validation",
          "operator",
          "validate_archive_semantics",
        ),
        Triple(
          "remote copied bytes need an exact inventory",
          "operator",
          "validate_remote_byte_inventory",
        ),
        Triple(
          "selection must rerun archive validation",
          "operator",
          "validate_archive \"\$attempt\" \"\$implementation\"",
        ),
        Triple(
          "archive history must prove ancestry",
          "operator",
          "git_no_hooks merge-base --is-ancestor",
        ),
        Triple("archive-only mode is mandatory", "operator", "--archive-only"),
        Triple("persist-only mode is mandatory", "operator", "--persist-only"),
        Triple("validate-attempt mode is mandatory", "operator", "--validate-attempt"),
      )

    val CORE_SUPERVISOR_FILES =
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
  }
}

private object OperatorSourceContract {
  fun assertComplete(bundle: Map<String, String>) {
    val runner = bundle.getValue("runner")
    val supervisor = bundle.getValue("supervisor")
    val operator = bundle.getValue("operator")
    check("CS2A_AUTHENTICATED_UID" in runner && "CS2A_AUTHENTICATED_RUNNER_SHA" in runner)
    check("CONTROLLED_UID_FILE" !in runner && "HANDOFF_FILE" !in runner)
    check("readlink \"/proc/\$\$/fd/" in runner)
    check("jq -e -f" in runner)
    check("flock -n 9" in supervisor)
    check("--signal=TERM" in supervisor && "--kill-after=" in supervisor)
    check("trap 'handle_signal" in supervisor)
    check("run_root=\$(extract_run_root_marker" in supervisor)
    check("restore_governors \"\$STATE/original-governors.tsv\"" in supervisor)
    check("copy_final_state_to_run_root" in supervisor)
    check("controlled_uid_policy_is_provisioned" in supervisor)
    check("terminate_child_group" in supervisor)
    check("dzdo cat /opt/revoman-benchmark/cs2a-implementation-sha" in operator)
    check("ln \\\"\\${'$'}candidate\\\" \\\"\\${'$'}destination\\\"" in operator)
    check("! -path './evidence-sha256sums.txt'" in operator)
    check(".cs2a-archive-stage.XXXXXXXX" in operator)
    check("recover_publication_marker \"\$canonical\" \"\$marker\"" in operator)
    check("validate_archive_semantics" in operator)
    check("validate_remote_byte_inventory" in operator)
    check("validate_archive \"\$attempt\" \"\$implementation\"" in operator)
    check("git_no_hooks merge-base --is-ancestor" in operator)
    check(listOf("--archive-only", "--persist-only", "--validate-attempt").all { it in operator })
    check("verify_result_files" in operator)
    check(!Regex("verify --input .*manifests").containsMatchIn(operator))
  }
}
