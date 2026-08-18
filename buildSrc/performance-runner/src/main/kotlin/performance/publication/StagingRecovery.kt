/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.publication

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.Comparator
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.runner.RunnerExit

/** Host/profile-lock authority supplied from outside all orphan-controlled state. */
internal fun interface RecoveryAuthority {
  fun authorizedOperationInput(runToken: String): Path?
}

internal sealed interface RecoveryOutcome {
  val runToken: String

  data class Published(override val runToken: String, val target: Path) : RecoveryOutcome

  data class Quarantined(override val runToken: String, val target: Path) : RecoveryOutcome

  data class Retained(override val runToken: String) : RecoveryOutcome

  data class Failed(override val runToken: String) : RecoveryOutcome
}

/** Deterministically resumes only externally authorized, marker-bound staging transactions. */
internal class StagingRecovery private constructor(
  private val authority: RecoveryAuthority,
  private val command: PublicationCommand = SYSTEM_COMMAND,
  private val verifyDistribution: (Path) -> Boolean = { false },
) {
  fun recover(artifactRoot: Path): List<RecoveryOutcome> {
    if (!safeRoot(artifactRoot)) return emptyList()
    val reservations =
      Files.list(artifactRoot).use { paths ->
        paths
          .filter { path ->
            val name = path.fileName.toString()
            name.startsWith('.') && name.endsWith(RESERVATION_SUFFIX) &&
              Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
          }
          .sorted()
          .toList()
      }
    return reservations.mapNotNull { recoverReservation(artifactRoot, it) }
  }

  private fun recoverReservation(root: Path, reservation: Path): RecoveryOutcome? {
    val token = reservation.fileName.toString().removePrefix(".").removeSuffix(RESERVATION_SUFFIX)
    if (AtomicPublisher.verifyReservation(root, token) != reservation) {
      return RecoveryOutcome.Retained(token)
    }
    val state = parseState(reservation.resolve(AtomicPublisher.STATE), token) ?: return null
    val authorizedInput = authority.authorizedOperationInput(token)
      ?: return RecoveryOutcome.Retained(token)
    if (!sourceIsAuthorized(state.sourceRoot, authorizedInput)) {
      return RecoveryOutcome.Retained(token)
    }
    val staging = root.resolve(".$token.staging")
    val target = root.resolve(state.destination)
    if (Files.exists(target, NOFOLLOW_LINKS)) {
      if (safePublishedTarget(target, state)) {
        AtomicPublisher.removeReservation(
          AtomicPublisher.PublicationPaths(
            reservation,
            reservation.resolve("token"),
            reservation.resolve(AtomicPublisher.STATE),
            staging,
            target,
          ),
        )
        return RecoveryOutcome.Published(token, target)
      }
      return RecoveryOutcome.Failed(token)
    }
    if (!Files.exists(staging, NOFOLLOW_LINKS)) {
      val source = Path.of(state.sourceRoot)
      val outcome =
        when (state.contentKind) {
          PublicationContentKind.EVIDENCE ->
            AtomicPublisher.publish(
              AtomicPublicationRequest(source, root, token, state.terminal, state.destination),
              command,
            )
          PublicationContentKind.FROZEN_DISTRIBUTION ->
            AtomicPublisher.publishVerifiedDistribution(
              AtomicPublicationRequest(source, root, token, state.terminal, state.destination),
              verifyDistribution,
              command,
            )
        }
      return outcome.toRecovery(token)
    }
    if (!safePublishedTarget(staging, state)) {
      return quarantine(root, reservation, staging, token)
    }
    val arguments = AtomicPublisher.publicationCommand(staging, target)
    if (runCatching { command.execute(arguments) }.getOrDefault(1) != 0) {
      return RecoveryOutcome.Failed(token)
    }
    if (
      Files.exists(staging, NOFOLLOW_LINKS) ||
        !safePublishedTarget(target, state)
    ) {
      return RecoveryOutcome.Failed(token)
    }
    ChecksumManifest.fsync(target)
    ChecksumManifest.fsync(root)
    AtomicPublisher.removeReservation(
      AtomicPublisher.PublicationPaths(
        reservation,
        reservation.resolve("token"),
        reservation.resolve(AtomicPublisher.STATE),
        staging,
        target,
      ),
    )
    return RecoveryOutcome.Published(token, target)
  }

  private fun quarantine(
    root: Path,
    reservation: Path,
    staging: Path,
    token: String,
  ): RecoveryOutcome {
    cleanupOwnedTree(staging)
    Files.deleteIfExists(reservation.resolve(AtomicPublisher.STATE))
    ChecksumManifest.fsync(root)
    val source = Files.createTempDirectory("revoman-recovery-invalid-").toRealPath()
    return try {
      Files.createDirectory(source.resolve("INVALID"))
      writeFsynced(source.resolve("INVALID/reason"), "RECOVERY_UNSAFE\n".encodeToByteArray())
      writeFsynced(
        source.resolve("stderr.log"),
        "performance-runner: RECOVERY_UNSAFE\n".encodeToByteArray(),
      )
      ChecksumManifest.write(source)
      when (
        val outcome =
          AtomicPublisher.publish(
            AtomicPublicationRequest(
              source,
              root,
              token,
              RunnerExit.INTERNAL_OR_PUBLICATION_FAILED,
              "INVALID-$token",
            ),
            command,
          )
      ) {
        is PublicationOutcome.Published -> RecoveryOutcome.Quarantined(token, outcome.target)
        is PublicationOutcome.Rejected -> RecoveryOutcome.Failed(token)
      }
    } finally {
      cleanupOwnedTree(source)
    }
  }

  private fun parseState(path: Path, expectedToken: String): RecoveryState? = runCatching {
    require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
    val bytes = Files.readAllBytes(path)
    val document = CanonicalJson.parseStrict(bytes).asObject()
    require(CanonicalJson.encode(document).contentEquals(bytes))
    require(document.size() == 7)
    require(document.get("schemaVersion").asString() == "publication-state-v1")
    require(document.get("runToken").asString() == expectedToken)
    val destination = document.get("destination").asString()
    require(SAFE_TOKEN.matches(destination))
    val terminalCode = document.get("terminal").asInt()
    val terminal = RunnerExit.entries.single { it.code == terminalCode }
    val contentKind =
      PublicationContentKind.entries.single {
        it.stateValue == document.get("contentKind").asString()
      }
    RecoveryState(
      destination,
      document.get("sourceRoot").asString(),
      Sha256.parse(document.get("sourceManifestSha256").asString()),
      terminal,
      contentKind,
    )
  }.getOrNull()

  private fun safePublishedTarget(path: Path, state: RecoveryState): Boolean =
    !Files.isSymbolicLink(path) &&
      Files.isDirectory(path, NOFOLLOW_LINKS) &&
      when (state.contentKind) {
        PublicationContentKind.EVIDENCE -> ChecksumManifest.verify(path)
        PublicationContentKind.FROZEN_DISTRIBUTION ->
          runCatching { verifyDistribution(path) }.getOrDefault(false)
      } &&
      runCatching {
          Sha256.digest(path.resolve(state.contentKind.manifestPath)) == state.sourceManifestSha256
        }
        .getOrDefault(false)

  private fun safeRoot(root: Path): Boolean =
    root.isAbsolute && root == root.toAbsolutePath().normalize() &&
      !hasSymbolicLinkComponent(root) &&
      Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)

  private fun sourceIsAuthorized(sourceValue: String, operationInput: Path): Boolean =
    runCatching {
        require(operationInput.isAbsolute && operationInput == operationInput.toAbsolutePath().normalize())
        require(Files.isDirectory(operationInput, NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(operationInput))
        require(!hasSymbolicLinkComponent(operationInput))
        val source = Path.of(sourceValue)
        require(source.isAbsolute && source == source.toAbsolutePath().normalize())
        require(source.startsWith(operationInput))
        require(!hasSymbolicLinkComponent(source))
        true
      }
      .getOrDefault(false)

  private fun hasSymbolicLinkComponent(path: Path): Boolean {
    var current = path.root ?: return true
    return path.any { component ->
      current = current.resolve(component)
      Files.exists(current, NOFOLLOW_LINKS) && Files.isSymbolicLink(current)
    }
  }

  private fun cleanupOwnedTree(root: Path) {
    if (!Files.exists(root, NOFOLLOW_LINKS)) return
    Files.walk(root).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }

  private fun writeFsynced(path: Path, bytes: ByteArray) {
    FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
      val buffer = ByteBuffer.wrap(bytes)
      while (buffer.hasRemaining()) channel.write(buffer)
      channel.force(true)
    }
  }

  private fun PublicationOutcome.toRecovery(token: String): RecoveryOutcome =
    when (this) {
      is PublicationOutcome.Published -> RecoveryOutcome.Published(token, target)
      is PublicationOutcome.Rejected -> RecoveryOutcome.Failed(token)
    }

  private data class RecoveryState(
    val destination: String,
    val sourceRoot: String,
    val sourceManifestSha256: Sha256,
    val terminal: RunnerExit,
    val contentKind: PublicationContentKind,
  )

  companion object {
    @JvmSynthetic
    internal fun system(
      authority: RecoveryAuthority,
      verifyDistribution: (Path) -> Boolean,
    ): StagingRecovery =
      StagingRecovery(
        authority = authority,
        verifyDistribution = verifyDistribution,
      )

    @JvmSynthetic
    internal fun forTest(
      authority: RecoveryAuthority,
      command: PublicationCommand,
      verifyDistribution: (Path) -> Boolean = { false },
    ): StagingRecovery = StagingRecovery(authority, command, verifyDistribution)

    private val SYSTEM_COMMAND = PublicationCommand { arguments ->
      ProcessBuilder(arguments).inheritIO().start().waitFor()
    }
    private val SAFE_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private const val RESERVATION_SUFFIX = ".reservation"
  }
}
