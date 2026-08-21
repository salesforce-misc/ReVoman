/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.finalize

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermissions
import performance.compare.CaptureBundleVerifier
import performance.publication.PublicationCommand
import performance.publication.RecoveryAuthority
import performance.publication.StagingRecovery
import performance.support.CaptureBundleFixture
import performance.support.DistributionFixture

/** Every injected crash leaves either no public target or one complete verifier-accepted target. */
class FinalizationCrashMatrixTest :
  FunSpec(
    {
      test("diagnostic publication is recoverable at every durable transition") {
        val distribution = DistributionFixture.create().apply { prepareComparisonProtocol() }
        val source = CaptureBundleFixture.create(distribution).apply(::makeBoundedDiagnostic)

        FinalizationTransition.entries.forEach { crashAt ->
          withClue(crashAt) {
            val parent = Files.createTempDirectory("finalization-crash-").toRealPath()
            reserveForCrash(parent, "crash")
            val finalizer =
              EvidenceFinalizer.forTest(
                command = movingCrashCommand(),
                checkpoint = FinalizationCheckpoint { transition ->
                  if (transition == crashAt) error("crash:$transition")
                },
              )

            finalizer
              .finalizeDiagnostic(DiagnosticFinalizationRequest(source.root, parent, "crash"))
              .shouldBeInstanceOf<FinalizationOutcome.Rejected>()

            val target = parent.resolve("crash")
            if (Files.exists(target)) {
              CaptureBundleVerifier.verify(target).failures shouldBe emptyList()
            } else {
              StagingRecovery.forTest(
                  authority = RecoveryAuthority { token -> source.root.takeIf { token == "crash" } },
                  command = movingCrashCommand(),
                )
                .recover(parent)
              if (Files.exists(target)) {
                CaptureBundleVerifier.verify(target).failures shouldBe emptyList()
              } else {
                val invalid = parent.resolve("INVALID-crash")
                performance.publication.ChecksumManifest.verify(invalid) shouldBe true
                Files.readString(invalid.resolve("INVALID/reason")) shouldBe "RECOVERY_UNSAFE\n"
              }
            }
          }
        }
        source.close()
        distribution.close()
      }
    },
  )

private fun movingCrashCommand(): PublicationCommand = PublicationCommand { command ->
  Files.move(Path.of(command[4]), Path.of(command[5]), ATOMIC_MOVE)
  0
}

private fun reserveForCrash(parent: Path, token: String) {
  val reservation = parent.resolve(".$token.reservation")
  Files.createDirectory(reservation)
  Files.setPosixFilePermissions(reservation, PosixFilePermissions.fromString("rwx------"))
  Files.writeString(reservation.resolve("token"), "$token\n")
}
