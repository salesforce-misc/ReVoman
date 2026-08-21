/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.campaign

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import performance.distribution.DistributionLayout
import performance.distribution.VerifiedDistribution
import performance.hash.Sha256

data class ReceiptFileFact(
  val relativePath: String,
  val byteLength: Long,
  val sha256: Sha256,
)

@ConsistentCopyVisibility
data class PreconditioningReceipt internal constructor(
  val role: CaptureRole,
  val distributionRoot: Path,
  val manifestSha256: Sha256,
  val files: List<ReceiptFileFact>,
  val settleDuration: Duration,
  val sequence: Long,
  private val settlement: ReceiptSettlement,
) {
  internal fun settle() = settlement.apply()
}

internal class ReceiptSettlement(
  private val action: () -> Unit,
) {
  private val applied = AtomicBoolean()

  fun apply() {
    check(applied.compareAndSet(false, true))
    action()
  }
}

fun interface RolePreconditioner {
  fun prepare(role: CaptureRole, distribution: VerifiedDistribution): PreconditioningReceipt
}

/** Builds a full-byte receipt whose validator starts the settle after its final verification read. */
class DefaultRolePreconditioner(
  private val sleeper: (Duration) -> Unit,
  private val settleDuration: Duration = REQUIRED_SETTLE,
) : RolePreconditioner {
  private val receiptSequence = AtomicLong()

  init {
    require(settleDuration == REQUIRED_SETTLE)
  }

  override fun prepare(
    role: CaptureRole,
    distribution: VerifiedDistribution,
  ): PreconditioningReceipt {
    val snapshot = DistributionPreconditioningSnapshot.read(distribution.root)
    return PreconditioningReceipt(
      role = role,
      distributionRoot = snapshot.root,
      manifestSha256 = snapshot.manifestSha256,
      files = snapshot.files,
      settleDuration = settleDuration,
      sequence = receiptSequence.incrementAndGet(),
      settlement = ReceiptSettlement { sleeper(settleDuration) },
    )
  }

  private companion object {
    val REQUIRED_SETTLE: Duration = Duration.ofSeconds(10)
  }
}

internal object CampaignReceiptValidator {
  fun validate(
    receipt: PreconditioningReceipt,
    expectedRole: CaptureRole,
    distribution: VerifiedDistribution,
    expectedSettleDuration: Duration,
    expectedSequence: Long,
  ): Boolean =
    runCatching {
        val snapshot = DistributionPreconditioningSnapshot.read(distribution.root)
        val matches =
          receipt.role == expectedRole &&
          receipt.distributionRoot == snapshot.root &&
          receipt.manifestSha256 == snapshot.manifestSha256 &&
          receipt.files == snapshot.files &&
          receipt.settleDuration == expectedSettleDuration &&
          receipt.sequence == expectedSequence
        if (matches) receipt.settle()
        matches
      }
      .getOrDefault(false)
}

private data class DistributionPreconditioningSnapshot(
  val root: Path,
  val manifestSha256: Sha256,
  val files: List<ReceiptFileFact>,
) {
  companion object {
    fun read(requestedRoot: Path): DistributionPreconditioningSnapshot {
      val root = requestedRoot.toAbsolutePath().normalize()
      require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root))
      val objects = Files.walk(root).use { stream -> stream.toList() }
      require(
        objects.all { path ->
          path == root ||
            (!Files.isSymbolicLink(path) &&
              (Files.isDirectory(path, NOFOLLOW_LINKS) ||
                Files.isRegularFile(path, NOFOLLOW_LINKS)))
        },
      )
      val actualFiles =
        objects
          .asSequence()
          .filter { path -> Files.isRegularFile(path, NOFOLLOW_LINKS) }
          .map(root::relativize)
          .map(::portablePath)
          .sortedWith(::compareUnsignedUtf8)
          .toList()
      val manifestPath = DistributionLayout.CHECKSUM_MANIFEST
      require(manifestPath in actualFiles)
      val manifest = root.resolve(manifestPath)
      val manifestBytes = Files.readAllBytes(manifest)
      val declared = parseManifest(manifestBytes)
      require(declared.map(ManifestEntry::relativePath) == declared.map(ManifestEntry::relativePath).sortedWith(::compareUnsignedUtf8))
      require(declared.map(ManifestEntry::relativePath).distinct().size == declared.size)
      require(actualFiles == (declared.map(ManifestEntry::relativePath) + manifestPath).sortedWith(::compareUnsignedUtf8))
      declared.forEach { entry ->
        val path = root.resolve(entry.relativePath).normalize()
        require(path.startsWith(root))
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        require(Sha256.digest(path) == entry.sha256)
      }
      val facts =
        actualFiles.map { relativePath ->
          val path = root.resolve(relativePath)
          ReceiptFileFact(
            relativePath = relativePath,
            byteLength = Files.size(path),
            sha256 = Sha256.digest(path),
          )
        }
      return DistributionPreconditioningSnapshot(
        root = root,
        manifestSha256 = Sha256.digest(manifestBytes),
        files = facts,
      )
    }

    private fun parseManifest(bytes: ByteArray): List<ManifestEntry> {
      val text = bytes.decodeToString()
      require(text.endsWith('\n') && '\r' !in text)
      return text
        .removeSuffix("\n")
        .lineSequence()
        .map { line ->
          val match = CHECKSUM_LINE.matchEntire(line) ?: error("checksum line")
          val relativePath = match.groupValues[2]
          require(
            relativePath != DistributionLayout.CHECKSUM_MANIFEST &&
              DistributionLayout.isNormalizedRelativePath(relativePath),
          )
          ManifestEntry(Sha256.parse(match.groupValues[1]), relativePath)
        }
        .toList()
        .also { require(it.isNotEmpty()) }
    }

    private val CHECKSUM_LINE = Regex("([0-9a-f]{64})  ([^\\r\\n]+)")
  }
}

private data class ManifestEntry(
  val sha256: Sha256,
  val relativePath: String,
)

private fun portablePath(path: Path): String =
  (0 until path.nameCount).joinToString("/") { index -> path.getName(index).toString() }

private fun compareUnsignedUtf8(left: String, right: String): Int {
  val leftBytes = left.encodeToByteArray()
  val rightBytes = right.encodeToByteArray()
  val sharedLength = minOf(leftBytes.size, rightBytes.size)
  val differingIndex = (0 until sharedLength).firstOrNull { leftBytes[it] != rightBytes[it] }
  return differingIndex?.let { index ->
    (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
  } ?: (leftBytes.size - rightBytes.size)
}
