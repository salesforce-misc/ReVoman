package com.salesforce.revoman.input

import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test

class KickTest {
  @Test
  fun `'runOnlySteps' and 'skipSteps' cannot have intersection`() {
    val exception =
      shouldThrow<IllegalArgumentException> {
        Kick.configure().skipSteps("a", "b").runOnlySteps("b", "c").off()
      }
  }

  @Test
  fun `'haltOnAnyFailureExceptForSteps' should be empty when 'haltOnAnyFailure' is set to True`() {
    val exception =
      shouldThrow<IllegalArgumentException> {
        Kick.configure().haltOnAnyFailure(true).haltOnAnyFailureExceptForSteps("a", "b").off()
      }
  }
}
