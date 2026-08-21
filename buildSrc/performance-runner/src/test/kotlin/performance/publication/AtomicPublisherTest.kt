/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.publication

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermissions
import performance.runner.RunnerExit

/** Security and crash-consistency matrix for the only public-evidence publication boundary. */
class AtomicPublisherTest :
  FunSpec(
    {
      test("recursive manifest covers every regular file exactly once in UTF-8 order") {
        val root = Files.createTempDirectory("manifest-tree-").toRealPath()
        Files.createDirectories(root.resolve("z"))
        Files.createDirectories(root.resolve("a"))
        Files.writeString(root.resolve("z/last.txt"), "last")
        Files.writeString(root.resolve("a/first.txt"), "first")

        ChecksumManifest.write(root)

        Files.readAllLines(root.resolve("checksums.sha256")).map { it.substringAfter("  ") } shouldContainExactly
          listOf("a/first.txt", "z/last.txt")
        ChecksumManifest.verify(root) shouldBe true
      }

      test("manifest rejects symlinks special entries self inclusion and altered bytes") {
        val root = Files.createTempDirectory("manifest-hostile-").toRealPath()
        Files.writeString(root.resolve("payload"), "original")
        ChecksumManifest.write(root)
        Files.writeString(root.resolve("payload"), "altered")
        ChecksumManifest.verify(root) shouldBe false

        Files.delete(root.resolve("checksums.sha256"))
        Files.delete(root.resolve("payload"))
        Files.writeString(root.resolve("outside"), "outside")
        Files.createSymbolicLink(root.resolve("payload"), root.resolve("outside"))
        ChecksumManifest.create(root).isFailure shouldBe true
      }

      test("reservation is verified before staging creation or publication command") {
        val fixture = PublicationFixture.create(reservationToken = "stale-token")
        var invoked = false

        val outcome =
          AtomicPublisher.publish(fixture.request) {
            invoked = true
            0
          }

        outcome.shouldBeInstanceOf<PublicationOutcome.Rejected>().exit shouldBe
          RunnerExit.INPUT_OR_PREFLIGHT_INVALID
        invoked shouldBe false
        Files.exists(fixture.staging) shouldBe false
        Files.exists(fixture.target) shouldBe false
      }

      test("reservation ownership is rejected when POSIX attributes are unavailable") {
        val archive = Files.createTempFile("non-posix-reservation-", ".zip")
        Files.delete(archive)

        FileSystems.newFileSystem(URI.create("jar:${archive.toUri()}"), mapOf("create" to "true"))
          .use { fileSystem ->
            fileSystem.supportedFileAttributeViews().contains("posix") shouldBe false
            val parent = Files.createDirectory(fileSystem.getPath("/artifacts"))
            val reservation = Files.createDirectory(parent.resolve(".run-1.reservation"))
            Files.writeString(reservation.resolve("token"), "run-1\n")

            AtomicPublisher.verifyReservation(parent, "run-1") shouldBe null
          }
      }

      test("publication invokes only literal GNU no-copy no-clobber same-target move") {
        val fixture = PublicationFixture.create()
        var command = emptyList<String>()

        val outcome =
          AtomicPublisher.publish(fixture.request) { arguments ->
            command = arguments
            Files.move(fixture.staging, fixture.target, ATOMIC_MOVE)
            0
          }

        outcome.shouldBeInstanceOf<PublicationOutcome.Published>().apply {
          target shouldBe fixture.target
          exit shouldBe RunnerExit.SUCCESS
        }
        command shouldContainExactly
          listOf(
            "/usr/bin/mv",
            "-nT",
            "--no-copy",
            "--",
            fixture.staging.toString(),
            fixture.target.toString(),
          )
        Files.exists(fixture.staging) shouldBe false
        Files.isDirectory(fixture.target) shouldBe true
        ChecksumManifest.verify(fixture.target) shouldBe true
        Files.exists(fixture.reservation) shouldBe false
      }

      test("runner-verified freeze publication preserves the exact distribution tree") {
        val parent = Files.createTempDirectory("freeze-publication-").toRealPath()
        val source = Files.createTempDirectory("freeze-source-").toRealPath()
        Files.createDirectories(source.resolve("metadata"))
        Files.createDirectories(source.resolve("bin"))
        Files.writeString(source.resolve("metadata/distribution.sha256"), "frozen-manifest\n")
        val launcher = source.resolve("bin/performance-runner")
        Files.writeString(launcher, "frozen-runner\n")
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"))
        val before = treeSnapshot(source)
        val token = "freeze-1"
        val reservation = parent.resolve(".$token.reservation")
        Files.createDirectory(reservation)
        Files.setPosixFilePermissions(reservation, PosixFilePermissions.fromString("rwx------"))
        Files.writeString(reservation.resolve("token"), "$token\n")
        val target = parent.resolve(token)
        var verificationCount = 0

        val outcome =
          AtomicPublisher.publishVerifiedDistribution(
            request = AtomicPublicationRequest(source, parent, token),
            verifyDistribution = { root ->
              verificationCount += 1
              Files.readString(root.resolve("metadata/distribution.sha256")) == "frozen-manifest\n"
            },
            command = { command ->
              Files.move(Path.of(command[4]), Path.of(command[5]), ATOMIC_MOVE)
              0
            },
          )

        outcome.shouldBeInstanceOf<PublicationOutcome.Published>().target shouldBe target
        verificationCount shouldBe 4
        treeSnapshot(target) shouldBe before
        Files.exists(target.resolve("checksums.sha256")) shouldBe false
        Files.getPosixFilePermissions(target.resolve("bin/performance-runner")) shouldBe
          PosixFilePermissions.fromString("rwx------")
        Files.isExecutable(target.resolve("bin/performance-runner")) shouldBe true
      }

      test("a collision is exit eight with staging retained and destination unchanged") {
        val fixture = PublicationFixture.create()
        Files.createDirectory(fixture.target)
        Files.writeString(fixture.target.resolve("owner"), "other-run")

        val outcome = AtomicPublisher.publish(fixture.request) { 1 }

        outcome.shouldBeInstanceOf<PublicationOutcome.Rejected>().exit shouldBe
          RunnerExit.INTERNAL_OR_PUBLICATION_FAILED
        Files.readString(fixture.target.resolve("owner")) shouldBe "other-run"
        Files.isDirectory(fixture.staging) shouldBe true
        Files.isDirectory(fixture.reservation) shouldBe true
      }

      test("zero command status without exact postconditions is exit eight and retains ownership state") {
        val fixture = PublicationFixture.create()

        val outcome = AtomicPublisher.publish(fixture.request) { 0 }

        outcome.shouldBeInstanceOf<PublicationOutcome.Rejected>().exit shouldBe
          RunnerExit.INTERNAL_OR_PUBLICATION_FAILED
        Files.isDirectory(fixture.staging) shouldBe true
        Files.isDirectory(fixture.reservation) shouldBe true
        Files.exists(fixture.target) shouldBe false
      }

      test("publication failure supersedes an earlier terminal status") {
        RunnerExit.entries.filterNot { it == RunnerExit.INTERNAL_OR_PUBLICATION_FAILED }.forEach { prior ->
          val fixture = PublicationFixture.create(runToken = "run-${prior.code}", terminal = prior)
          AtomicPublisher.publish(fixture.request) { 1 }
            .shouldBeInstanceOf<PublicationOutcome.Rejected>()
            .exit shouldBe RunnerExit.INTERNAL_OR_PUBLICATION_FAILED
        }
      }
    },
  )

private fun treeSnapshot(root: Path): Map<String, ByteArray> =
  Files.walk(root).use { paths ->
    paths
      .filter(Files::isRegularFile)
      .map { path -> root.relativize(path).joinToString("/") to Files.readAllBytes(path) }
      .toList()
      .toMap()
  }

private class PublicationFixture private constructor(
  val parent: Path,
  val source: Path,
  val reservation: Path,
  val staging: Path,
  val target: Path,
  val request: AtomicPublicationRequest,
) {
  companion object {
    fun create(
      runToken: String = "run-1",
      reservationToken: String = runToken,
      terminal: RunnerExit = RunnerExit.SUCCESS,
    ): PublicationFixture {
      val parent = Files.createTempDirectory("atomic-publication-").toRealPath()
      val source = Files.createTempDirectory("immutable-source-").toRealPath()
      Files.createDirectories(source.resolve("children"))
      Files.writeString(source.resolve("root.json"), "{}\n")
      Files.writeString(source.resolve("children/data.txt"), "data\n")
      ChecksumManifest.write(source)
      val reservation = parent.resolve(".$runToken.reservation")
      Files.createDirectory(reservation)
      Files.setPosixFilePermissions(reservation, PosixFilePermissions.fromString("rwx------"))
      Files.writeString(reservation.resolve("token"), "$reservationToken\n")
      val staging = parent.resolve(".$runToken.staging")
      val target = parent.resolve(runToken)
      return PublicationFixture(
        parent,
        source,
        reservation,
        staging,
        target,
        AtomicPublicationRequest(source, parent, runToken, terminal),
      )
    }
  }
}
