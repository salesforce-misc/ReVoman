package com.salesforce.revoman.input

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KickTest {
  @Test
  fun `'runOnlySteps' and 'skipSteps' cannot have intersection`() {
    val exception = shouldThrow<IllegalArgumentException> { Kick.configure().skipSteps("a", "b").runOnlySteps("b", "c").off() }
    exception.message shouldBe "'runOnlySteps' and 'skipSteps' cannot have intersection"
  }

  @Test
  fun `'haltOnAnyFailureExceptForSteps' should be empty when 'haltOnAnyFailure' is set to True`() {
    val exception = shouldThrow<IllegalArgumentException> { Kick.configure().haltOnAnyFailure(true).haltOnAnyFailureExceptForSteps("a", "b").off() }
    exception.message shouldBe "'haltOnAnyFailureExceptForSteps' should be empty when 'haltOnAnyFailure' is set to True"
  }
}
