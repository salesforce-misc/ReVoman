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
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.runner.RunnerExit
import tools.jackson.databind.node.JsonNodeFactory

internal data class AtomicPublicationRequest(
  val sourceRoot: Path,
  val artifactParent: Path,
  val runToken: String,
  val terminal: RunnerExit = RunnerExit.SUCCESS,
  val destinationName: String = runToken,
)

internal enum class PublicationContentKind(val stateValue: String, val manifestPath: String) {
  EVIDENCE("evidence", "checksums.sha256"),
  FROZEN_DISTRIBUTION("frozenDistribution", "metadata/distribution.sha256"),
}

internal fun interface PublicationContentVerifier {
  fun verify(root: Path): Boolean
}

internal sealed interface PublicationOutcome {
  data class Published(val target: Path, val exit: RunnerExit) : PublicationOutcome

  data class Rejected(val exit: RunnerExit) : PublicationOutcome
}

internal enum class PublicationTransition {
  RESERVATION_VERIFIED,
  STAGING_CREATED,
  SOURCE_COPIED,
  MANIFEST_VERIFIED,
  STAGING_DURABLE,
  BEFORE_PUBLICATION,
  AFTER_PUBLICATION,
  TARGET_VERIFIED,
  RESERVATION_REMOVED,
}

internal fun interface PublicationCheckpoint {
  fun reached(transition: PublicationTransition)
}

internal fun interface PublicationCommand {
  fun execute(arguments: List<String>): Int
}

/** Owns the sole exact no-copy publication transition. */
internal object AtomicPublisher {
  fun publish(request: AtomicPublicationRequest): PublicationOutcome =
    publish(request, EVIDENCE_VERIFIER, SYSTEM_COMMAND, NOOP_CHECKPOINT)

  @JvmSynthetic
  internal fun publish(
    request: AtomicPublicationRequest,
    command: PublicationCommand,
  ): PublicationOutcome = publish(request, EVIDENCE_VERIFIER, command, NOOP_CHECKPOINT)

  @JvmSynthetic
  internal fun publish(
    request: AtomicPublicationRequest,
    command: PublicationCommand,
    checkpoint: PublicationCheckpoint,
  ): PublicationOutcome = publish(request, EVIDENCE_VERIFIER, command, checkpoint)

  @JvmSynthetic
  internal fun publishVerifiedDistribution(
    request: AtomicPublicationRequest,
    verifyDistribution: (Path) -> Boolean,
    command: PublicationCommand,
    checkpoint: PublicationCheckpoint = NOOP_CHECKPOINT,
  ): PublicationOutcome =
    publish(
      request,
      ContentVerification(
        PublicationContentKind.FROZEN_DISTRIBUTION,
        PublicationContentVerifier(verifyDistribution),
      ),
      command,
      checkpoint,
    )

  private fun publish(
    request: AtomicPublicationRequest,
    content: ContentVerification,
    command: PublicationCommand,
    checkpoint: PublicationCheckpoint,
  ): PublicationOutcome {
    val paths = validate(request, content)
      ?: return PublicationOutcome.Rejected(RunnerExit.INPUT_OR_PREFLIGHT_INVALID)
    return runCatching {
        val sourceManifest = Files.readAllBytes(request.sourceRoot.resolve(content.kind.manifestPath))
        val state = stateBytes(request, content.kind, sourceManifest)
        writeState(paths.state, state)
        ChecksumManifest.fsync(paths.reservation)
        checkpoint.reached(PublicationTransition.RESERVATION_VERIFIED)
        require(!Files.exists(paths.staging, NOFOLLOW_LINKS))
        Files.createDirectory(paths.staging)
        setOwnerOnly(paths.staging)
        checkpoint.reached(PublicationTransition.STAGING_CREATED)
        copyTree(
          request.sourceRoot,
          paths.staging,
          preservePermissions = content.kind == PublicationContentKind.FROZEN_DISTRIBUTION,
        )
        checkpoint.reached(PublicationTransition.SOURCE_COPIED)
        require(content.verifier.verify(paths.staging))
        require(
          Files.readAllBytes(paths.staging.resolve(content.kind.manifestPath))
            .contentEquals(sourceManifest),
        )
        require(content.verifier.verify(request.sourceRoot))
        require(
          Files.readAllBytes(request.sourceRoot.resolve(content.kind.manifestPath))
            .contentEquals(sourceManifest),
        )
        checkpoint.reached(PublicationTransition.MANIFEST_VERIFIED)
        fsyncTree(paths.staging)
        ChecksumManifest.fsync(request.artifactParent)
        checkpoint.reached(PublicationTransition.STAGING_DURABLE)
        checkpoint.reached(PublicationTransition.BEFORE_PUBLICATION)
        val arguments = publicationCommand(paths.staging, paths.target)
        require(command.execute(arguments) == 0)
        checkpoint.reached(PublicationTransition.AFTER_PUBLICATION)
        require(!Files.exists(paths.staging, NOFOLLOW_LINKS))
        require(Files.isDirectory(paths.target, NOFOLLOW_LINKS) && !Files.isSymbolicLink(paths.target))
        require(content.verifier.verify(paths.target))
        require(
          Files.readAllBytes(paths.target.resolve(content.kind.manifestPath))
            .contentEquals(sourceManifest),
        )
        ChecksumManifest.fsync(paths.target)
        ChecksumManifest.fsync(request.artifactParent)
        checkpoint.reached(PublicationTransition.TARGET_VERIFIED)
        removeReservation(paths)
        checkpoint.reached(PublicationTransition.RESERVATION_REMOVED)
        PublicationOutcome.Published(paths.target, request.terminal)
      }
      .getOrElse { PublicationOutcome.Rejected(RunnerExit.INTERNAL_OR_PUBLICATION_FAILED) }
  }

  internal fun publicationCommand(source: Path, target: Path): List<String> =
    listOf("/usr/bin/mv", "-nT", "--no-copy", "--", source.toString(), target.toString())

  private fun validate(
    request: AtomicPublicationRequest,
    content: ContentVerification,
  ): PublicationPaths? = runCatching {
    require(SAFE_TOKEN.matches(request.runToken) && SAFE_TOKEN.matches(request.destinationName))
    val parent = request.artifactParent
    val source = request.sourceRoot
    require(parent.isAbsolute && parent == parent.toAbsolutePath().normalize())
    require(source.isAbsolute && source == source.toAbsolutePath().normalize())
    require(!hasSymbolicLinkComponent(parent) && !hasSymbolicLinkComponent(source))
    require(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent))
    require(Files.isDirectory(source, NOFOLLOW_LINKS) && !Files.isSymbolicLink(source))
    require(!source.startsWith(parent) && !parent.startsWith(source))
    require(content.verifier.verify(source))
    require(
      Files.isRegularFile(source.resolve(content.kind.manifestPath), NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(source.resolve(content.kind.manifestPath)),
    )
    val reservation = requireNotNull(verifyReservation(parent, request.runToken))
    val token = reservation.resolve("token")
    PublicationPaths(
      reservation = reservation,
      token = token,
      state = reservation.resolve(STATE),
      staging = parent.resolve(".${request.runToken}.staging"),
      target = parent.resolve(request.destinationName),
    )
  }.getOrNull()

  internal fun verifyReservation(parent: Path, runToken: String): Path? = runCatching {
    require(SAFE_TOKEN.matches(runToken))
    require(parent.isAbsolute && parent == parent.toAbsolutePath().normalize())
    require(!hasSymbolicLinkComponent(parent))
    require(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent))
    val reservation = parent.resolve(".$runToken.reservation")
    val token = reservation.resolve("token")
    require(Files.isDirectory(reservation, NOFOLLOW_LINKS) && !Files.isSymbolicLink(reservation))
    require(ownerOnly(reservation))
    require(Files.isRegularFile(token, NOFOLLOW_LINKS) && !Files.isSymbolicLink(token))
    require(Files.size(token) <= 256)
    require(Files.readString(token) == "$runToken\n")
    reservation
  }.getOrNull()

  internal fun removeReservation(paths: PublicationPaths) {
    Files.deleteIfExists(paths.state)
    Files.delete(paths.token)
    Files.delete(paths.reservation)
    ChecksumManifest.fsync(paths.reservation.parent)
  }

  private fun stateBytes(
    request: AtomicPublicationRequest,
    contentKind: PublicationContentKind,
    sourceManifest: ByteArray,
  ): ByteArray =
    CanonicalJson.encode(
      JsonNodeFactory.instance.objectNode().apply {
        put("contentKind", contentKind.stateValue)
        put("destination", request.destinationName)
        put("runToken", request.runToken)
        put("schemaVersion", "publication-state-v1")
        put("sourceManifestSha256", Sha256.digest(sourceManifest).hex)
        put("sourceRoot", request.sourceRoot.toString())
        put("terminal", request.terminal.code)
      },
    )

  private fun writeState(path: Path, bytes: ByteArray) {
    if (Files.exists(path, NOFOLLOW_LINKS)) {
      require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
      require(Files.readAllBytes(path).contentEquals(bytes))
      return
    }
    writeFsynced(path, bytes)
  }

  private fun copyTree(
    source: Path,
    target: Path,
    preservePermissions: Boolean,
  ) {
    Files.walk(source).use { paths ->
      paths.forEach { path ->
        require(!Files.isSymbolicLink(path))
        val destination = target.resolve(source.relativize(path).toString())
        when {
          path == source -> Unit
          Files.isDirectory(path, NOFOLLOW_LINKS) -> {
            Files.createDirectory(destination)
            setOwnerOnly(destination)
          }
          Files.isRegularFile(path, NOFOLLOW_LINKS) ->
            writeFsynced(destination, Files.readAllBytes(path))
          else -> error("unsupported publication entry")
        }
        if (preservePermissions && path != source && Files.isRegularFile(path, NOFOLLOW_LINKS)) {
          copyPermissions(path, destination)
        }
      }
    }
    if (preservePermissions) {
      Files.walk(source).use { paths ->
        paths
          .filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
          .sorted(Comparator.reverseOrder())
          .forEach { path ->
            val destination = target.resolve(source.relativize(path).toString())
            copyPermissions(path, destination)
          }
      }
    }
  }

  private fun copyPermissions(source: Path, destination: Path) {
    Files.setPosixFilePermissions(destination, Files.getPosixFilePermissions(source, NOFOLLOW_LINKS))
    ChecksumManifest.fsync(destination)
  }

  private fun fsyncTree(root: Path) {
    Files.walk(root).use { paths ->
      paths
        .filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
        .sorted(Comparator.reverseOrder())
        .forEach(ChecksumManifest::fsync)
    }
  }

  private fun writeFsynced(path: Path, bytes: ByteArray) {
    FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
      val buffer = ByteBuffer.wrap(bytes)
      while (buffer.hasRemaining()) channel.write(buffer)
      channel.force(true)
    }
  }

  private fun ownerOnly(path: Path): Boolean = runCatching {
    Files.getPosixFilePermissions(path) ==
      setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
      )
  }.getOrDefault(false)

  private fun setOwnerOnly(path: Path) {
    Files.setPosixFilePermissions(
      path,
      setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
      ),
    )
    require(ownerOnly(path))
  }

  private fun hasSymbolicLinkComponent(path: Path): Boolean {
    var current = path.root ?: return true
    return path.any { component ->
      current = current.resolve(component)
      Files.exists(current, NOFOLLOW_LINKS) && Files.isSymbolicLink(current)
    }
  }

  internal data class PublicationPaths(
    val reservation: Path,
    val token: Path,
    val state: Path,
    val staging: Path,
    val target: Path,
  )

  private data class ContentVerification(
    val kind: PublicationContentKind,
    val verifier: PublicationContentVerifier,
  )

  private val SYSTEM_COMMAND = PublicationCommand { arguments ->
    ProcessBuilder(arguments).inheritIO().start().waitFor()
  }
  private val NOOP_CHECKPOINT = PublicationCheckpoint {}
  private val EVIDENCE_VERIFIER =
    ContentVerification(
      PublicationContentKind.EVIDENCE,
      PublicationContentVerifier(ChecksumManifest::verify),
    )
  private val SAFE_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
  internal const val STATE = "publication-state.json"
}
