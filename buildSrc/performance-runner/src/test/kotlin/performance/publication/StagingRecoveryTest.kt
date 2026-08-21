/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.publication

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermissions

/** Recovery authority is external to orphan-controlled bytes and every recovery is idempotent. */
class StagingRecoveryTest :
  FunSpec(
    {
      test("externally authorized checksum-valid owned staging resumes exact publication") {
        val fixture = orphanedPublication("recover-valid")
        val recovery =
          StagingRecovery.forTest(
            authority = RecoveryAuthority { token -> fixture.source.takeIf { token == "recover-valid" } },
            command = movingCommand(),
          )

        recovery.recover(fixture.parent).single().shouldBeInstanceOf<RecoveryOutcome.Published>()
        Files.isDirectory(fixture.target) shouldBe true
        ChecksumManifest.verify(fixture.target) shouldBe true
        Files.exists(fixture.staging) shouldBe false
        Files.exists(fixture.reservation) shouldBe false
        recovery.recover(fixture.parent) shouldBe emptyList()
      }

      test("authority denial retains orphan bytes and performs no side effect") {
        val fixture = orphanedPublication("recover-denied")
        var invoked = false
        val outcomes =
          StagingRecovery.forTest(
              authority = RecoveryAuthority { null },
              command = {
                invoked = true
                0
              },
            )
            .recover(fixture.parent)

        outcomes.shouldHaveSize(1)
        outcomes.single().shouldBeInstanceOf<RecoveryOutcome.Retained>()
        invoked shouldBe false
        Files.isDirectory(fixture.staging) shouldBe true
        Files.isDirectory(fixture.reservation) shouldBe true
      }

      test("authorized token cannot redirect recovery outside its authorized operation input") {
        val fixture = orphanedPublication("recover-source-escape")
        val authorizedInput = Files.createTempDirectory("authorized-operation-input-").toRealPath()
        var invoked = false

        val outcome =
          StagingRecovery.forTest(
              authority =
                RecoveryAuthority { token ->
                  authorizedInput.takeIf { token == "recover-source-escape" }
                },
              command = {
                invoked = true
                0
              },
            )
            .recover(fixture.parent)
            .single()

        outcome.shouldBeInstanceOf<RecoveryOutcome.Retained>()
        invoked shouldBe false
        Files.isDirectory(fixture.staging) shouldBe true
        Files.isDirectory(fixture.reservation) shouldBe true
        Files.isDirectory(fixture.source) shouldBe true
      }

      test("an unlabeled or unreserved staging-shaped directory is never inferred to be owned") {
        val parent = Files.createTempDirectory("recovery-unowned-").toRealPath()
        Files.createDirectory(parent.resolve(".guess.staging"))
        Files.writeString(parent.resolve(".guess.staging/private.txt"), "do-not-touch")

        StagingRecovery.forTest(
            authority = RecoveryAuthority { parent },
            command = { error("unowned staging must not invoke publication") },
          )
          .recover(parent) shouldBe emptyList()
        Files.readString(parent.resolve(".guess.staging/private.txt")) shouldBe "do-not-touch"
      }

      test("a symlinked artifact-root ancestor is rejected before recovery") {
        val fixture = orphanedPublication("recover-root-symlink")
        val ancestorLink = fixture.parent.resolveSibling("${fixture.parent.fileName}-ancestor")
        Files.createSymbolicLink(ancestorLink, fixture.parent.parent)
        val symlinkedRoot = ancestorLink.resolve(fixture.parent.fileName)

        val outcomes =
          StagingRecovery.forTest(
              authority = RecoveryAuthority { error("unsafe root must not consult authority") },
              command = { error("unsafe root must not invoke publication") },
            )
            .recover(symlinkedRoot)

        outcomes shouldBe emptyList()
        Files.isDirectory(fixture.staging) shouldBe true
        Files.isDirectory(fixture.reservation) shouldBe true
      }

      test("authorized hostile staging is quarantined without publishing attacker bytes") {
        val fixture = orphanedPublication("recover-hostile")
        Files.delete(fixture.staging.resolve("children/data.txt"))
        Files.createSymbolicLink(
          fixture.staging.resolve("children/data.txt"),
          fixture.parent.resolve("outside-secret"),
        )
        Files.writeString(fixture.parent.resolve("outside-secret"), "secret")

        val outcome =
          StagingRecovery.forTest(
              authority = RecoveryAuthority { fixture.source },
              command = movingCommand(),
            )
            .recover(fixture.parent)
            .single()

        outcome.shouldBeInstanceOf<RecoveryOutcome.Quarantined>()
        Files.exists(fixture.target) shouldBe false
        Files.readString(fixture.parent.resolve("outside-secret")) shouldBe "secret"
        val quarantine = outcome.target
        Files.isDirectory(quarantine) shouldBe true
        Files.readString(quarantine.resolve("INVALID/reason")) shouldBe "RECOVERY_UNSAFE\n"
        ChecksumManifest.verify(quarantine) shouldBe true
      }

      test("destination collision retains staging and the pre-existing destination byte-for-byte") {
        val fixture = orphanedPublication("recover-collision")
        Files.createDirectory(fixture.target)
        Files.writeString(fixture.target.resolve("owner"), "existing")

        val outcome =
          StagingRecovery.forTest(
              authority = RecoveryAuthority { fixture.source },
              command = { 1 },
            )
            .recover(fixture.parent)
            .single()

        outcome.shouldBeInstanceOf<RecoveryOutcome.Failed>()
        Files.readString(fixture.target.resolve("owner")) shouldBe "existing"
        Files.isDirectory(fixture.staging) shouldBe true
        Files.isDirectory(fixture.reservation) shouldBe true
      }
    },
  )

private data class RecoveryFixture(
  val parent: Path,
  val source: Path,
  val reservation: Path,
  val staging: Path,
  val target: Path,
)

private fun orphanedPublication(token: String): RecoveryFixture {
  val fixture = PublicationFixtureForRecovery.create(token)
  AtomicPublisher.publish(fixture.request) { 1 }
    .shouldBeInstanceOf<PublicationOutcome.Rejected>()
  return RecoveryFixture(
    fixture.parent,
    fixture.source,
    fixture.reservation,
    fixture.staging,
    fixture.target,
  )
}

private fun movingCommand(): PublicationCommand = PublicationCommand { command ->
  Files.move(Path.of(command[4]), Path.of(command[5]), ATOMIC_MOVE)
  0
}

private class PublicationFixtureForRecovery private constructor(
  val parent: Path,
  val source: Path,
  val reservation: Path,
  val staging: Path,
  val target: Path,
  val request: AtomicPublicationRequest,
) {
  companion object {
    fun create(runToken: String): PublicationFixtureForRecovery {
      val parent = Files.createTempDirectory("recovery-parent-").toRealPath()
      val source = Files.createTempDirectory("recovery-source-").toRealPath()
      Files.createDirectories(source.resolve("children"))
      Files.writeString(source.resolve("root.json"), "{}\n")
      Files.writeString(source.resolve("children/data.txt"), "data\n")
      ChecksumManifest.write(source)
      val reservation = parent.resolve(".$runToken.reservation")
      Files.createDirectory(reservation)
      Files.setPosixFilePermissions(reservation, PosixFilePermissions.fromString("rwx------"))
      Files.writeString(reservation.resolve("token"), "$runToken\n")
      return PublicationFixtureForRecovery(
        parent,
        source,
        reservation,
        parent.resolve(".$runToken.staging"),
        parent.resolve(runToken),
        AtomicPublicationRequest(source, parent, runToken),
      )
    }
  }
}
