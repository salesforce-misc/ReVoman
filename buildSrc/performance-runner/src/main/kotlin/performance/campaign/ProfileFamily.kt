package performance.campaign

import java.time.Duration

/** One immutable profile policy; the ladder is part of the policy identity. */
enum class ProfileFamily(
  val id: String,
  val forkLadder: List<Int>,
  val settleDuration: Duration = Duration.ofSeconds(5),
  val maximumSessionDuration: Duration = Duration.ofHours(2),
) {
  COLD("cold", listOf(10, 20, 40)),
  WARM("warm", listOf(10, 20, 40)),
}
