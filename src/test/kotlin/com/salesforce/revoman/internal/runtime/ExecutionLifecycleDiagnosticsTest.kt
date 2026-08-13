/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.salesforce.revoman.compat.JvmSurfaceInventory
import com.salesforce.revoman.compat.JvmSurfaceKind
import com.salesforce.revoman.compat.configuredRootJar
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.postman.PostmanEnvironment
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.ArrayList
import org.junit.jupiter.api.Test

class ExecutionLifecycleDiagnosticsTest {
  @Test
  fun `diagnostics facade has the exact synthetic static drain descriptor`() {
    URLClassLoader(
        arrayOf(configuredRootJar().toUri().toURL()),
        ClassLoader.getPlatformClassLoader(),
      )
      .use { loader ->
        val facade = Class.forName(DIAGNOSTICS_CLASS, false, loader)
        val drain = facade.getDeclaredMethod("drain")
        assertThat(Modifier.isStatic(drain.modifiers)).isTrue()
        assertThat(drain.returnType).isEqualTo(Array<Any>::class.java)
      }
    val surface = JvmSurfaceInventory.readJar(configuredRootJar())
    assertThat(
        surface
          .single {
            it.owner == DIAGNOSTICS_OWNER && it.kind == JvmSurfaceKind.METHOD && it.name == "drain"
          }
          .descriptor
      )
      .isEqualTo("()[Ljava/lang/Object;")
  }

  @Test
  fun `disabled and malformed modes allocate no records and initialization is immutable`() {
    runChild("disabled")
    listOf("", "weak-references", "WEAK-REFERENCES-V1", "weak-references-v1 ", "true").forEach {
      value ->
      runChild("malformed", value)
    }
  }

  @Test
  fun `enabled mode records one exact weak reference for each execution and drains once`() {
    runChild("enabled")
  }

  @Test
  fun `drain swaps the queue and releases its old backing storage`() {
    runChild("queue-swap")
  }

  @Test
  fun `malicious record buffers fail atomically`() {
    listOf(
        "odd",
        "non-string",
        "blank",
        "unknown",
        "weak-subclass",
        "repeated-reference",
      )
      .forEach { mutation -> runChild("malicious", mutation) }
    runChild("overflow")
  }

  private fun runChild(vararg arguments: String) {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val command =
      listOf(
        java,
        "-Drevoman.banner=off",
        "-cp",
        System.getProperty("java.class.path"),
        CHILD_MAIN,
      ) + arguments
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    assertWithMessage("child diagnostics output:\n$output").that(process.waitFor()).isEqualTo(0)
    assertThat(output.trim()).isEqualTo("OK")
  }

  private companion object {
    const val DIAGNOSTICS_CLASS =
      "com.salesforce.revoman.internal.runtime.ExecutionLifecycleDiagnostics"
    const val DIAGNOSTICS_OWNER =
      "com/salesforce/revoman/internal/runtime/ExecutionLifecycleDiagnostics"
    const val CHILD_MAIN =
      "com.salesforce.revoman.internal.runtime.ExecutionLifecycleDiagnosticsTestKt"
  }
}

/** Fresh-process entry point so diagnostics initialization cannot leak between test cases. */
fun main(arguments: Array<String>) {
  when (arguments.first()) {
    "disabled" -> verifyDisabledInitialization()
    "malformed" -> verifyMalformedInitialization(arguments[1])
    "enabled" -> verifyEnabledDrain()
    "queue-swap" -> verifyQueueSwap()
    "malicious" -> verifyMaliciousBuffer(arguments[1])
    "overflow" -> verifyCountOverflow()
    else -> error("unknown diagnostics child action: ${arguments.first()}")
  }
  println("OK")
}

private fun verifyDisabledInitialization() {
  System.clearProperty(DIAGNOSTICS_PROPERTY)
  createOneKickExecution()
  check(drainLifecycleDiagnostics().isEmpty())
  val facade = diagnosticsFacade()
  check(
    facade.declaredFields
      .filterNot { field -> field.type.isPrimitive || field.type == String::class.java }
      .all { field -> field.also { it.isAccessible = true }.get(null) == null }
  )

  System.setProperty(DIAGNOSTICS_PROPERTY, DIAGNOSTICS_VALUE)
  createOneKickExecution()
  check(drainLifecycleDiagnostics().isEmpty())
}

private fun verifyMalformedInitialization(value: String) {
  System.setProperty(DIAGNOSTICS_PROPERTY, value)
  createOneKickExecution()
  check(drainLifecycleDiagnostics().isEmpty())
}

private fun verifyEnabledDrain() {
  System.setProperty(DIAGNOSTICS_PROPERTY, DIAGNOSTICS_VALUE)
  diagnosticsFacade()
  System.clearProperty(DIAGNOSTICS_PROPERTY)
  createOneKickExecution()
  val live = recordsField().get(null) as ArrayList<*>
  check(live.size == 4)
  live.indices.forEach { index ->
    when (index % 2) {
      0 -> check(live[index] is String)
      else -> {
        val reference = live[index]
        check(reference is WeakReference<*> && reference.javaClass === WeakReference::class.java)
      }
    }
  }
  val drained = drainLifecycleDiagnostics()

  check(drained.javaClass === Array<Any>::class.java)
  check(drained.size == 4)
  check(drained.filterIsInstance<String>() == listOf("ExecutionSession", "KickExecution"))
  val references = drained.filterIsInstance<WeakReference<*>>()
  check(references.size == 2)
  check(references.all { reference -> reference.javaClass === WeakReference::class.java })
  check(references[0] !== references[1])
  check(drainLifecycleDiagnostics().isEmpty())
}

private fun verifyCountOverflow() {
  System.setProperty(DIAGNOSTICS_PROPERTY, DIAGNOSTICS_VALUE)
  drainLifecycleDiagnostics()
  val count = diagnosticsFacade().getDeclaredField("recordCount").also { it.isAccessible = true }
  count.setLong(null, Long.MAX_VALUE)

  val failure = runCatching { createOneKickExecution() }.exceptionOrNull()
  check(failure is ArithmeticException)
  check(count.getLong(null) == Long.MAX_VALUE)
  check((recordsField().get(null) as ArrayList<*>).isEmpty())
}

private fun verifyQueueSwap() {
  System.setProperty(DIAGNOSTICS_PROPERTY, DIAGNOSTICS_VALUE)
  createOneKickExecution()
  val queueReference = currentQueueReference()
  check(drainLifecycleDiagnostics().size == 4)
  awaitCleared(queueReference)
  check(currentQueueReference().get() != null)
}

private fun verifyMaliciousBuffer(mutation: String) {
  System.setProperty(DIAGNOSTICS_PROPERTY, DIAGNOSTICS_VALUE)
  drainLifecycleDiagnostics()
  val reference = WeakReference(Any())
  val records: ArrayList<Any> =
    when (mutation) {
      "odd" -> arrayListOf("ExecutionSession")
      "non-string" -> arrayListOf(1, WeakReference(Any()))
      "blank" -> arrayListOf(" ", WeakReference(Any()))
      "unknown" -> arrayListOf("Unknown", WeakReference(Any()))
      "weak-subclass" -> arrayListOf("ExecutionSession", WeakSubclass(Any()))
      "repeated-reference" -> arrayListOf("ExecutionSession", reference, "KickExecution", reference)
      else -> error("unknown mutation: $mutation")
    }
  recordsField().set(null, records)
  diagnosticsFacade()
    .getDeclaredField("recordCount")
    .also { it.isAccessible = true }
    .setLong(null, records.size.toLong() / 2)

  repeat(2) {
    val failure =
      runCatching { drainLifecycleDiagnostics() }.exceptionOrNull()
        ?: error("malicious diagnostics queue was accepted: $mutation")
    check(failure is IllegalStateException || failure is IllegalArgumentException)
  }
}

private fun createOneKickExecution() {
  val session =
    executionSession(
      emptyMap(),
      KickExecutionFactory { kick, environment ->
        kickExecution(
          configuredKick = kick,
          effectiveDynamicEnvironment = environment,
          body = KickBody { emptyRundown() },
          sandboxFactory = SandboxFactory { error("no script should create a sandbox") },
        )
      },
    )
  session.executeKick(Kick.configure().off(), carryForward = false)
  session.close()
}

private fun emptyRundown(): Rundown =
  Rundown(
    mutableEnv = PostmanEnvironment(mutableMapOf()),
    haltOnFailureOfTypeExcept = emptyMap(),
    providedStepsToExecuteCount = 0,
  )

private fun diagnosticsFacade(): Class<*> = Class.forName(DIAGNOSTICS_CLASS_NAME)

private fun recordsField() =
  diagnosticsFacade()
    .declaredFields
    .single { field ->
      ArrayList::class.java.isAssignableFrom(field.type)
    }
    .also { field -> field.isAccessible = true }

private fun currentQueueReference(): WeakReference<Any> {
  @Suppress("UNCHECKED_CAST")
  return WeakReference(recordsField().get(null) as Any)
}

private fun awaitCleared(reference: WeakReference<*>) {
  repeat(100) {
    if (reference.get() == null) return
    System.gc()
    Thread.sleep(10)
  }
  check(reference.get() == null) { "drained diagnostics queue remained reachable" }
}

private class WeakSubclass(value: Any) : WeakReference<Any>(value)

private const val DIAGNOSTICS_CLASS_NAME =
  "com.salesforce.revoman.internal.runtime.ExecutionLifecycleDiagnostics"
private const val DIAGNOSTICS_PROPERTY = "revoman.lifecycleDiagnostics"
private const val DIAGNOSTICS_VALUE = "weak-references-v1"
