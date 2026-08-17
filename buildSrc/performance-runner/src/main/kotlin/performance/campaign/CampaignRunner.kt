package performance.campaign

import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.nio.file.Files
import performance.capture.CaptureOutcome
import performance.capture.CaptureRunner
import performance.compare.RegressionPolicy
import performance.distribution.VerifiedDistribution
import performance.model.CaptureIdentity

data class CampaignRequest(
  val baseline: VerifiedDistribution,
  val candidate: VerifiedDistribution,
  val profileFamily: ProfileFamily,
  val session: SessionIdentity,
  val provisionalRoot: Path,
  val regressionPolicy: RegressionPolicy?,
)

fun interface CampaignCapturePort {
  fun capture(role: CaptureRole, forks: Int, distribution: VerifiedDistribution, identity: CaptureIdentity, root: Path): CaptureOutcome
}

class CampaignRunner private constructor(
  private val capturePort: CampaignCapturePort,
  private val calibrationEvaluator: ProvisionalCalibrationEvaluator,
  private val rolePreconditioner: RolePreconditioner,
  private val clock: Clock,
  @Suppress("UNUSED_PARAMETER") private val marker: Unit,
) {
  constructor(@Suppress("UNUSED_PARAMETER") captureRunner: CaptureRunner, evaluator: ProvisionalCalibrationEvaluator, preconditioner: RolePreconditioner, clock: Clock) : this(
    CampaignCapturePort { _, _, _, _, _ -> error("capture profile selection is adapter-owned") }, evaluator, preconditioner, clock,
    Unit,
  )

  constructor(port: CampaignCapturePort, evaluator: ProvisionalCalibrationEvaluator, rolePreconditioner: RolePreconditioner, clock: Clock = Clock.systemUTC()) : this(port, evaluator, rolePreconditioner, clock, Unit)

  fun run(request: CampaignRequest): CampaignProvisionalOutcome {
    val started = clock.instant()
    var sequence = 0
    val captures = mutableListOf<CampaignCapture>()
    var permanentlyInvalid = false
    var previousReceipt: PreconditioningReceipt? = null
    fun attempt(role: CaptureRole, forks: Int, distribution: VerifiedDistribution): CampaignCapture? {
      if (permanentlyInvalid) return null
      val elapsed = java.time.Duration.between(started, clock.instant())
      if (elapsed > request.profileFamily.maximumSessionDuration) {
        permanentlyInvalid = true
        return null
      }
      val receipt = runCatching { rolePreconditioner.prepare(role, distribution) }.getOrNull()
      if (receipt == null || !validReceipt(receipt, role, distribution, previousReceipt, request.profileFamily)) {
        permanentlyInvalid = true
        return null
      }
      previousReceipt = receipt
      val identity = CaptureIdentity("${request.session.campaignId}-$role-$forks", "${request.session.campaignId}-$role-$forks", request.session.performanceSessionId, ++sequence)
      val outcome = capturePort.capture(role, forks, distribution, identity, request.provisionalRoot)
      val capture = CampaignCapture(role, forks, identity, outcome, selected = false)
      captures += capture
      if (outcome !is CaptureOutcome.Provisional || outcome.document.profile.profiler != "none") permanentlyInvalid = true
      if (Duration.between(started, clock.instant()) > request.profileFamily.maximumSessionDuration) permanentlyInvalid = true
      return capture
    }
    for (forks in request.profileFamily.forkLadder) {
      val a1 = attempt(CaptureRole.BASELINE_A1, forks, request.baseline) ?: break
      val a2 = attempt(CaptureRole.BASELINE_A2, forks, request.baseline) ?: break
      val first = a1.outcome as? CaptureOutcome.Provisional ?: break
      val second = a2.outcome as? CaptureOutcome.Provisional ?: break
      val decision = calibrationEvaluator.evaluate(ValidatedProvisionalCapture(CaptureRole.BASELINE_A1, first.document), ValidatedProvisionalCapture(CaptureRole.BASELINE_A2, second.document))
      if (!decision.passed) continue
      val selectedA1 = a1.copy(selected = true).also { captures[captures.indexOf(a1)] = it }
      val selectedA2 = a2.copy(selected = true).also { captures[captures.indexOf(a2)] = it }
      val b = attempt(CaptureRole.CANDIDATE_B, forks, request.candidate)
      if (b == null || permanentlyInvalid) return CampaignProvisionalOutcome(CampaignStatus.INVALID, captures, selectedA1, selectedA2, reason = "campaign invalid")
      return CampaignProvisionalOutcome(CampaignStatus.QUALIFIED, captures, selectedA1, selectedA2, b.copy(selected = true).also { captures[captures.indexOf(b)] = it })
    }
    return CampaignProvisionalOutcome(if (permanentlyInvalid) CampaignStatus.INVALID else CampaignStatus.CALIBRATION_EXHAUSTED, captures, reason = "calibration exhausted")
  }

  private fun validReceipt(
    receipt: PreconditioningReceipt,
    role: CaptureRole,
    distribution: VerifiedDistribution,
    previous: PreconditioningReceipt?,
    family: ProfileFamily,
  ): Boolean {
    if (receipt.role != role || receipt.distributionRoot != distribution.root.toAbsolutePath().normalize()) return false
    if (receipt.settleDuration != family.settleDuration || previous?.settleDuration != null && previous.settleDuration != receipt.settleDuration) return false
    if (previous != null && receipt.sequence != previous.sequence + 1) return false
    val expected = runCatching {
      Files.walk(receipt.distributionRoot).use { stream ->
        stream.filter { Files.isRegularFile(it) }.map { receipt.distributionRoot.relativize(it).toString().replace('\\', '/') }.toList()
      }.sortedWith(Comparator { a, b -> compareUtf8(a, b) })
    }.getOrNull() ?: return false
    if (receipt.files.map(ReceiptFileFact::relativePath) != expected) return false
    return receipt.files.all { fact ->
      val path = receipt.distributionRoot.resolve(fact.relativePath)
      Files.size(path) == fact.size && sha256(path) == fact.sha256
    }
  }

  private fun compareUtf8(a: String, b: String): Int {
    val left = a.toByteArray(StandardCharsets.UTF_8)
    val right = b.toByteArray(StandardCharsets.UTF_8)
    for (index in 0 until minOf(left.size, right.size)) {
      val comparison = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
      if (comparison != 0) return comparison
    }
    return left.size - right.size
  }

  private fun sha256(path: Path): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
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

}
