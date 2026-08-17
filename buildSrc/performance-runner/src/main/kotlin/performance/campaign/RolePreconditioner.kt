package performance.campaign

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import performance.distribution.VerifiedDistribution

data class ReceiptFileFact(val relativePath: String, val size: Long, val sha256: String)

data class PreconditioningReceipt(
  val role: CaptureRole,
  val distributionRoot: Path,
  val files: List<ReceiptFileFact>,
  val settleDuration: Duration,
  val sequence: Long,
)

fun interface RolePreconditioner {
  fun prepare(role: CaptureRole, distribution: VerifiedDistribution): PreconditioningReceipt
}

/** Reads all frozen distribution bytes in canonical UTF-8 relative-path order before settling. */
class DefaultRolePreconditioner(
  private val sleeper: (Duration) -> Unit = { duration -> Thread.sleep(duration.toMillis()) },
  private val settleDuration: Duration = Duration.ofSeconds(5),
) : RolePreconditioner {
  private var receiptSequence = 0L

  override fun prepare(role: CaptureRole, distribution: VerifiedDistribution): PreconditioningReceipt {
    val root = distribution.root.toAbsolutePath().normalize()
    val files: List<String> = Files.walk(root).use { stream ->
      stream.filter { path -> Files.isRegularFile(path) }
        .map { path -> root.relativize(path).toString().replace('\\', '/') }
        .toList()
    }.sortedWith(Comparator { left, right -> compareUtf8(left, right) })
    val facts = files.map { relative ->
      val path = root.resolve(relative)
      ReceiptFileFact(relative, Files.size(path), digest(path))
    }
    sleeper(settleDuration)
    return PreconditioningReceipt(role, root, facts, settleDuration, ++receiptSequence)
  }

  private fun digest(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  private fun compareUtf8(left: String, right: String): Int {
    val a = left.toByteArray(StandardCharsets.UTF_8)
    val b = right.toByteArray(StandardCharsets.UTF_8)
    for (index in 0 until minOf(a.size, b.size)) {
      val comparison = (a[index].toInt() and 0xff) - (b[index].toInt() and 0xff)
      if (comparison != 0) return comparison
    }
    return a.size - b.size
  }
}
