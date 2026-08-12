/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResourceScopeTest {
  @Test
  fun `resources close once in reverse registration order`() {
    val closed = mutableListOf<String>()
    val first = RecordingCloseable("first", closed)
    val second = RecordingCloseable("second", closed)
    val third = RecordingCloseable("third", closed)
    val scope = resourceScope()

    assertSame(first, scope.own(first))
    assertSame(second, scope.own(second))
    assertSame(third, scope.own(third))
    scope.close()

    assertThat(closed).containsExactly("third", "second", "first").inOrder()
    assertThat(listOf(first.closeCount, second.closeCount, third.closeCount))
      .containsExactly(1, 1, 1)
  }

  @Test
  fun `empty scope closes cleanly`() {
    assertDoesNotThrow { resourceScope().close() }
  }

  @Test
  fun `repeated close is a no-op`() {
    val closed = mutableListOf<String>()
    val resource = RecordingCloseable("only", closed)
    val scope = resourceScope()
    scope.own(resource)

    scope.close()
    scope.close()

    assertThat(closed).containsExactly("only")
    assertThat(resource.closeCount).isEqualTo(1)
  }

  @Test
  fun `close failure is not retried by a second close`() {
    val closed = mutableListOf<String>()
    val closeFailure = CloseFailure("only")
    val resource = RecordingCloseable("only", closed, closeFailure)
    val scope = resourceScope()
    scope.own(resource)

    val thrown = assertThrows<CloseFailure> { scope.close() }
    assertSame(closeFailure, thrown)
    assertDoesNotThrow { scope.close() }

    assertThat(closed).containsExactly("only")
    assertThat(resource.closeCount).isEqualTo(1)
  }

  @Test
  fun `first reverse-order close failure is primary and later failures are suppressed`() {
    val closed = mutableListOf<String>()
    val firstFailure = CloseFailure("first")
    val secondFailure = CloseFailure("second")
    val thirdFailure = CloseFailure("third")
    val scope = resourceScope()
    scope.own(RecordingCloseable("first", closed, firstFailure))
    scope.own(RecordingCloseable("second", closed, secondFailure))
    scope.own(RecordingCloseable("third", closed, thirdFailure))

    val thrown = assertThrows<CloseFailure> { scope.close() }

    assertSame(thirdFailure, thrown)
    assertThat(thrown.suppressed.asList()).containsExactly(secondFailure, firstFailure).inOrder()
    assertThat(closed).containsExactly("third", "second", "first").inOrder()
  }

  @Test
  fun `body failure stays primary and receives every close failure`() {
    val closed = mutableListOf<String>()
    val bodyFailure = BodyFailure()
    val firstFailure = CloseFailure("first")
    val secondFailure = CloseFailure("second")
    val first = RecordingCloseable("first", closed, firstFailure)
    val second = RecordingCloseable("second", closed, secondFailure)
    val scope = resourceScope()
    scope.own(first)
    scope.own(second)

    val thrown = assertThrows<BodyFailure> { scope.useInternal { throw bodyFailure } }

    assertSame(bodyFailure, thrown)
    assertThat(thrown.suppressed.asList()).containsExactly(secondFailure, firstFailure).inOrder()
    assertThat(secondFailure.suppressed).isEmpty()
    assertThat(firstFailure.suppressed).isEmpty()
    assertThat(closed).containsExactly("second", "first").inOrder()
    assertThat(second.closeCount).isEqualTo(1)
    assertThat(first.closeCount).isEqualTo(1)

    assertDoesNotThrow { scope.close() }
    assertThat(second.closeCount).isEqualTo(1)
    assertThat(first.closeCount).isEqualTo(1)
  }

  @Test
  fun `body Error stays primary and receives every close Error in reverse order`() {
    val closed = mutableListOf<String>()
    val bodyFailure = BodyError()
    val firstFailure = CloseError("first")
    val secondFailure = CloseError("second")
    val scope = resourceScope()
    scope.own(RecordingCloseable("first", closed, firstFailure))
    scope.own(RecordingCloseable("second", closed, secondFailure))

    val thrown = assertThrows<BodyError> { scope.useInternal { throw bodyFailure } }

    assertSame(bodyFailure, thrown)
    assertThat(thrown.suppressed.asList()).containsExactly(secondFailure, firstFailure).inOrder()
    assertThat(closed).containsExactly("second", "first").inOrder()
  }

  @Test
  fun `useInternal returns the body result and closes the resource`() {
    val closed = mutableListOf<String>()
    val scope = resourceScope()
    scope.own(RecordingCloseable("owned", closed))

    val result = scope.useInternal { "body-result" }

    assertThat(result).isEqualTo("body-result")
    assertThat(closed).containsExactly("owned")
  }

  @Test
  fun `generic useInternal makes its close failure primary after a successful body`() {
    val closeFailure = CloseError("generic-close")
    val resource = RecordingCloseable("generic", mutableListOf(), closeFailure)
    var bodyCompleted = false

    val thrown =
      assertThrows<CloseError> {
        resource.useInternal {
          bodyCompleted = true
          bodyCompleted.toString()
        }
      }

    assertSame(closeFailure, thrown)
    assertThat(bodyCompleted).isTrue()
    assertThat(thrown.suppressed).isEmpty()
    assertThat(resource.closeCount).isEqualTo(1)
  }

  @Test
  fun `generic useInternal keeps a body Error primary and directly suppresses close failure`() {
    val bodyFailure = BodyError()
    val closeFailure = CloseError("generic-close")
    val resource = RecordingCloseable("generic", mutableListOf(), closeFailure)

    val thrown = assertThrows<BodyError> { resource.useInternal { throw bodyFailure } }

    assertSame(bodyFailure, thrown)
    assertThat(thrown.suppressed).hasLength(1)
    assertSame(closeFailure, thrown.suppressed.single())
    assertThat(resource.closeCount).isEqualTo(1)
  }

  @Test
  fun `registration after close rejects and closes the offered resource`() {
    val closed = mutableListOf<String>()
    val offered = RecordingCloseable("offered", closed)
    val scope = resourceScope()
    scope.close()

    assertThrows<IllegalStateException> { scope.own(offered) }

    assertThat(closed).containsExactly("offered")
    assertThat(offered.closeCount).isEqualTo(1)
  }

  @Test
  fun `registration failure stays primary when the rejected resource close fails`() {
    val closed = mutableListOf<String>()
    val closeFailure = CloseError("rejected")
    val offered = RecordingCloseable("offered", closed, closeFailure)
    val scope = resourceScope()
    scope.close()

    val thrown = assertThrows<IllegalStateException> { scope.own(offered) }

    assertThat(closed).containsExactly("offered")
    assertThat(offered.closeCount).isEqualTo(1)
    assertThat(thrown.suppressed).hasLength(1)
    assertSame(closeFailure, thrown.suppressed.single())
  }

  private class RecordingCloseable(
    private val name: String,
    private val closed: MutableList<String>,
    private val failure: Throwable? = null,
  ) : InternalCloseable {
    var closeCount: Int = 0
      private set

    override fun close() {
      closeCount++
      closed += name
      failure?.let { throw it }
    }
  }

  private class CloseFailure(name: String) : RuntimeException(name)

  private class BodyFailure : RuntimeException("body")

  private class CloseError(name: String) : Error(name)

  private class BodyError : Error("body")
}
