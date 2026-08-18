/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.scenario

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.testing.http.MockHttpHandler
import com.salesforce.revoman.testing.http.MockHttpServer
import com.salesforce.revoman.testing.http.RecordedHttpRequest
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.http4k.core.Body
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class RevUpV3ScenarioContractTest {
  @TempDir lateinit var temporaryDirectory: Path

  @Test
  fun `packaged fixture has the exact canonical tree identity`() {
    val identity = RevUpV3Scenario.packagedFixtureIdentityForTest()

    assertThat(identity.canonicalJson).isEqualTo(EXPECTED_CANONICAL_MANIFEST)
    assertThat(identity.sha256).isEqualTo(EXPECTED_TREE_SHA256)
  }

  @Test
  fun `packaged tree manifest is canonical and bound to verified fixture bytes`() {
    val manifests =
      RevUpV3Scenario::class.java.classLoader.getResources(PACKAGED_MANIFEST_PATH).toList()

    assertThat(manifests).hasSize(1)
    val manifest = manifests.single().openStream().use { it.readAllBytes().toString(UTF_8) }
    assertThat(manifest).isEqualTo(EXPECTED_CANONICAL_MANIFEST)
    assertThat(RevUpV3Scenario.packagedFixtureIdentityForTest().canonicalJson).isEqualTo(manifest)
  }

  @Test
  fun `fixture verification rejects an unexpected regular file`() {
    val fixture = writeFixture(temporaryDirectory)
    Files.writeString(fixture.resolve("unexpected.yaml"), "unexpected\n")

    val failure =
      assertThrows<IllegalArgumentException> {
        RevUpV3Scenario.verifyFixtureDirectoryForTest(fixture)
      }

    assertThat(failure).hasMessageThat().contains("unexpected")
  }

  @Test
  fun `fixture verification rejects a symbolic link`() {
    val fixture = writeFixture(temporaryDirectory)
    Files.createSymbolicLink(
      fixture.resolve("linked.yaml"),
      fixture.resolve("benchmark.request.yaml"),
    )

    val failure =
      assertThrows<IllegalArgumentException> {
        RevUpV3Scenario.verifyFixtureDirectoryForTest(fixture)
      }

    assertThat(failure).hasMessageThat().contains("symbolic link")
  }

  @Test
  fun `fixture archive identity comes from verified entry bytes`() {
    val archive = writeFixtureArchive(temporaryDirectory.resolve("valid.jar"))

    val identity = RevUpV3Scenario.fixtureIdentityForArchiveForTest(archive)

    assertThat(identity.canonicalJson).isEqualTo(EXPECTED_CANONICAL_MANIFEST)
    assertThat(identity.sha256).isEqualTo(EXPECTED_TREE_SHA256)
  }

  @Test
  fun `fixture archive rejects a manifest that does not match verified entry bytes`() {
    val archive =
      writeFixtureArchive(
        temporaryDirectory.resolve("mismatched-manifest.jar"),
        manifest = "[]\n",
      )

    val failure =
      assertThrows<IllegalArgumentException> {
        RevUpV3Scenario.fixtureIdentityForArchiveForTest(archive)
      }

    assertThat(failure).hasMessageThat().contains("tree manifest")
  }

  @Test
  fun `fixture archive rejects a duplicate fixture entry`() {
    val archive =
      patchCentralEntry(
        writeFixtureArchive(temporaryDirectory.resolve("duplicate.jar"), includeAlias = true),
        "$FIXTURE_ARCHIVE_ROOT/benchmark.requesx.yaml",
        replacementName = "$FIXTURE_ARCHIVE_ROOT/benchmark.request.yaml",
      )

    val failure =
      assertThrows<IllegalArgumentException> {
        RevUpV3Scenario.fixtureIdentityForArchiveForTest(archive)
      }

    assertThat(failure).hasMessageThat().contains("duplicate")
  }

  @Test
  fun `fixture archive rejects a symbolic-link fixture entry`() {
    val archive =
      patchCentralEntry(
        writeFixtureArchive(temporaryDirectory.resolve("symlink.jar")),
        "$FIXTURE_ARCHIVE_ROOT/benchmark.request.yaml",
        unixMode = UNIX_SYMBOLIC_LINK_MODE,
      )

    val failure =
      assertThrows<IllegalArgumentException> {
        RevUpV3Scenario.fixtureIdentityForArchiveForTest(archive)
      }

    assertThat(failure).hasMessageThat().contains("symbolic link")
  }

  @Test
  fun `fixture archive rejects another non-regular fixture entry`() {
    val archive =
      patchCentralEntry(
        writeFixtureArchive(temporaryDirectory.resolve("fifo.jar")),
        "$FIXTURE_ARCHIVE_ROOT/benchmark.request.yaml",
        unixMode = UNIX_FIFO_MODE,
      )

    val failure =
      assertThrows<IllegalArgumentException> {
        RevUpV3Scenario.fixtureIdentityForArchiveForTest(archive)
      }

    assertThat(failure).hasMessageThat().contains("non-regular")
  }

  @Test
  fun `two successful operations each use real wire and never reuse the learned ledger`() {
    RevUpV3Scenario.start().use { scenario ->
      val first = scenario.execute()
      assertSuccessfulRundown(first)
      scenario.verifyInvocation()

      val second = scenario.execute()
      assertSuccessfulRundown(second)
      scenario.verifyInvocation()

      assertThat(first.learnedLedger).isNotSameInstanceAs(second.learnedLedger)
    }
  }

  @Test
  fun `malformed V3 request fails eagerly and closes once with close failure suppressed`() {
    val fixture = writeFixture(temporaryDirectory)
    Files.writeString(fixture.resolve("benchmark.request.yaml"), "not: [valid\n")
    val closeFailure = IllegalStateException("close failed")
    val closeStarted = CountDownLatch(1)
    val allowClose = CountDownLatch(1)
    val closeCount = AtomicInteger()
    val scenario =
      RevUpV3Scenario.startForTest(
        RevUpV3ScenarioTestHooks(
          fixtureRoot = fixture,
          fixtureIdentity = RevUpV3Scenario.fixtureIdentityForDirectoryForTest(fixture),
          serverFactory =
            blockingFailingServerFactory(
              closeStarted,
              allowClose,
              closeCount,
              closeFailure,
            ),
        )
      )
    val executor = Executors.newSingleThreadExecutor()

    try {
      val future = executor.submit<Rundown> { scenario.execute() }
      assertThat(closeStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
      scenario.close()
      allowClose.countDown()

      val failure =
        assertThrows<ExecutionException> {
            future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          }
          .cause!!
      assertThat(failure).isNotSameInstanceAs(closeFailure)
      assertThat(failure.suppressed.toList()).containsExactly(closeFailure)
      assertThat(closeCount.get()).isEqualTo(1)
      scenario.close()
      assertThat(closeCount.get()).isEqualTo(1)
    } finally {
      allowClose.countDown()
      executor.shutdownNow()
      assertThat(executor.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    }
  }

  @Test
  fun `malformed environment fails without dispatch and closes eagerly`() {
    val fixture = writeFixture(temporaryDirectory)
    Files.writeString(fixture.resolve("benchmark.environment.yaml"), "values: [broken\n")
    val closeCount = AtomicInteger()
    val scenario =
      RevUpV3Scenario.startForTest(
        RevUpV3ScenarioTestHooks(
          fixtureRoot = fixture,
          fixtureIdentity = RevUpV3Scenario.fixtureIdentityForDirectoryForTest(fixture),
          serverFactory = countingServerFactory(closeCount),
        )
      )

    val failure = assertThrows<Throwable> { scenario.execute() }

    assertThat(failure).isNotNull()
    assertThat(closeCount.get()).isEqualTo(1)
    scenario.close()
    assertThat(closeCount.get()).isEqualTo(1)
  }

  @Test
  fun `malformed response is a verification failure with close failure suppressed`() {
    val closeFailure = IllegalStateException("close failed")
    val closeCount = AtomicInteger()
    val scenario =
      RevUpV3Scenario.startForTest(
        RevUpV3ScenarioTestHooks(
          responseFactory = {
            Response(OK)
              .header("Content-Type", EXPECTED_CONTENT_TYPE)
              .body(Body("{".byteInputStream()))
          },
          serverFactory = failingServerFactory(closeCount, closeFailure),
        )
      )

    scenario.execute()
    val failure = assertThrows<IllegalStateException> { scenario.verifyInvocation() }

    assertThat(failure).hasMessageThat().contains("successful report")
    assertThat(failure.suppressed.toList()).containsExactly(closeFailure)
    assertThat(closeCount.get()).isEqualTo(1)
  }

  @Test
  fun `handler mismatch fails verification and eagerly closes`() {
    val fixture = writeFixture(temporaryDirectory)
    Files.writeString(
      fixture.resolve("benchmark.environment.yaml"),
      ENVIRONMENT_YAML.replace("fixture-marker", "wrong-marker"),
    )
    val closeCount = AtomicInteger()
    val scenario =
      RevUpV3Scenario.startForTest(
        RevUpV3ScenarioTestHooks(
          fixtureRoot = fixture,
          fixtureIdentity = RevUpV3Scenario.fixtureIdentityForDirectoryForTest(fixture),
          serverFactory = countingServerFactory(closeCount),
        )
      )

    scenario.execute()
    val failure = assertThrows<IllegalStateException> { scenario.verifyInvocation() }

    assertThat(failure).hasMessageThat().contains("handler call")
    assertThat(closeCount.get()).isEqualTo(1)
  }

  @Test
  fun `startup acquisition is transactional and preserves setup failure as primary`() {
    val setupFailure = IllegalArgumentException("kick setup failed")
    val closeFailure = IllegalStateException("close failed")
    val closeCount = AtomicInteger()

    val failure =
      assertThrows<IllegalArgumentException> {
        RevUpV3Scenario.startForTest(
          RevUpV3ScenarioTestHooks(
            afterServerStart = { throw setupFailure },
            serverFactory = failingServerFactory(closeCount, closeFailure),
          )
        )
      }

    assertThat(failure).isSameInstanceAs(setupFailure)
    assertThat(failure.suppressed.toList()).containsExactly(closeFailure)
    assertThat(closeCount.get()).isEqualTo(1)
  }

  @Test
  fun `closing an unverified invocation preserves verification failure over close failure`() {
    val closeFailure = IllegalStateException("close failed")
    val closeCount = AtomicInteger()
    val scenario =
      RevUpV3Scenario.startForTest(
        RevUpV3ScenarioTestHooks(serverFactory = failingServerFactory(closeCount, closeFailure))
      )
    scenario.execute()

    val failure = assertThrows<IllegalStateException> { scenario.close() }

    assertThat(failure).hasMessageThat().contains("unverified invocation")
    assertThat(failure.suppressed.toList()).containsExactly(closeFailure)
    scenario.close()
    assertThat(closeCount.get()).isEqualTo(1)
  }

  private fun writeFixture(parent: Path): Path =
    parent.resolve("revup-v3").also { root ->
      Files.createDirectories(root.resolve(".resources"))
      Files.writeString(root.resolve(".resources/definition.yaml"), DEFINITION_YAML)
      Files.writeString(root.resolve("benchmark.environment.yaml"), ENVIRONMENT_YAML)
      Files.writeString(root.resolve("benchmark.request.yaml"), REQUEST_YAML)
    }

  private fun writeFixtureArchive(
    path: Path,
    includeAlias: Boolean = false,
    manifest: String = EXPECTED_CANONICAL_MANIFEST,
  ): Path {
    val entries =
      linkedMapOf(
        "$FIXTURE_ARCHIVE_ROOT/.resources/definition.yaml" to DEFINITION_YAML,
        "$FIXTURE_ARCHIVE_ROOT/benchmark.environment.yaml" to ENVIRONMENT_YAML,
        "$FIXTURE_ARCHIVE_ROOT/benchmark.request.yaml" to REQUEST_YAML,
        PACKAGED_MANIFEST_PATH to manifest,
      )
    if (includeAlias) {
      entries["$FIXTURE_ARCHIVE_ROOT/benchmark.requesx.yaml"] = REQUEST_YAML
    }
    JarOutputStream(Files.newOutputStream(path)).use { archive ->
      entries.forEach { (name, contents) ->
        archive.putNextEntry(JarEntry(name).apply { time = 0 })
        archive.write(contents.toByteArray(UTF_8))
        archive.closeEntry()
      }
    }
    return path
  }

  private fun patchCentralEntry(
    archive: Path,
    entryName: String,
    replacementName: String = entryName,
    unixMode: Int? = null,
  ): Path {
    val bytes = Files.readAllBytes(archive)
    val end =
      (bytes.size - ZIP_END_MINIMUM_SIZE downTo 0).first {
        readUnsignedInt(bytes, it) == ZIP_END_SIGNATURE
      }
    val entryCount = readUnsignedShort(bytes, end + ZIP_END_ENTRY_COUNT_OFFSET)
    var cursor = readUnsignedInt(bytes, end + ZIP_END_DIRECTORY_OFFSET).toInt()
    var patched = 0
    repeat(entryCount) {
      check(readUnsignedInt(bytes, cursor) == ZIP_CENTRAL_SIGNATURE)
      val nameLength = readUnsignedShort(bytes, cursor + ZIP_CENTRAL_NAME_LENGTH_OFFSET)
      val extraLength = readUnsignedShort(bytes, cursor + ZIP_CENTRAL_EXTRA_LENGTH_OFFSET)
      val commentLength = readUnsignedShort(bytes, cursor + ZIP_CENTRAL_COMMENT_LENGTH_OFFSET)
      val nameStart = cursor + ZIP_CENTRAL_HEADER_SIZE
      val currentName = bytes.copyOfRange(nameStart, nameStart + nameLength).toString(UTF_8)
      if (currentName == entryName) {
        val replacement = replacementName.toByteArray(UTF_8)
        check(replacement.size == nameLength)
        replacement.copyInto(bytes, nameStart)
        unixMode?.let { mode ->
          bytes[cursor + ZIP_CENTRAL_VERSION_PLATFORM_OFFSET] = ZIP_UNIX_PLATFORM.toByte()
          writeUnsignedInt(
            bytes,
            cursor + ZIP_CENTRAL_EXTERNAL_ATTRIBUTES_OFFSET,
            mode.toLong() shl 16,
          )
        }
        patched += 1
      }
      cursor += ZIP_CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
    }
    check(patched == 1) { "Expected exactly one archive entry named $entryName" }
    Files.write(archive, bytes)
    return archive
  }

  private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

  private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xff) or
      ((bytes[offset + 1].toLong() and 0xff) shl 8) or
      ((bytes[offset + 2].toLong() and 0xff) shl 16) or
      ((bytes[offset + 3].toLong() and 0xff) shl 24)

  private fun writeUnsignedInt(bytes: ByteArray, offset: Int, value: Long) {
    repeat(Int.SIZE_BYTES) { index -> bytes[offset + index] = (value shr (index * 8)).toByte() }
  }

  private fun countingServerFactory(closeCount: AtomicInteger): (MockHttpHandler) -> RevUpV3Server =
    { handler ->
      serverAdapter(MockHttpServer.start(handler), closeCount)
    }

  private fun failingServerFactory(
    closeCount: AtomicInteger,
    closeFailure: RuntimeException,
  ): (MockHttpHandler) -> RevUpV3Server = { handler ->
    serverAdapter(MockHttpServer.start(handler), closeCount, closeFailure = closeFailure)
  }

  private fun blockingFailingServerFactory(
    closeStarted: CountDownLatch,
    allowClose: CountDownLatch,
    closeCount: AtomicInteger,
    closeFailure: RuntimeException,
  ): (MockHttpHandler) -> RevUpV3Server = { handler ->
    serverAdapter(
      MockHttpServer.start(handler),
      closeCount,
      beforeClose = {
        closeStarted.countDown()
        check(allowClose.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          "test did not release scenario close"
        }
      },
      closeFailure = closeFailure,
    )
  }

  private fun serverAdapter(
    delegate: MockHttpServer,
    closeCount: AtomicInteger,
    beforeClose: () -> Unit = {},
    closeFailure: RuntimeException? = null,
  ): RevUpV3Server =
    object : RevUpV3Server {
      override val baseUrl: String = delegate.baseUrl

      override fun requests(): List<RecordedHttpRequest> = delegate.requests()

      override fun close() {
        closeCount.incrementAndGet()
        beforeClose()
        delegate.close()
        closeFailure?.let { throw it }
      }
    }

  private fun assertSuccessfulRundown(rundown: Rundown) {
    assertThat(rundown.stepReports).hasSize(1)
    val report = rundown.stepReports.single()
    assertThat(report.isSuccessful).isTrue()
    assertThat(report.isLedgerSkipped).isFalse()
    assertThat(report.pmTestAssertions).hasSize(1)
    assertThat(report.pmTestAssertions.single().name).isEqualTo("benchmark response")
    assertThat(report.pmTestAssertions.single().passed).isTrue()
    assertThat(report.pmTestAssertions.single().skipped).isFalse()
    assertThat(rundown.mutableEnv["id"]).isEqualTo(42)
  }

  private companion object {
    const val TEST_TIMEOUT_SECONDS = 10L
    const val FIXTURE_ARCHIVE_ROOT = "performance/revup-v3"
    const val UNIX_SYMBOLIC_LINK_MODE = 0xa1ff
    const val UNIX_FIFO_MODE = 0x11a4
    const val ZIP_END_SIGNATURE = 0x06054b50L
    const val ZIP_CENTRAL_SIGNATURE = 0x02014b50L
    const val ZIP_END_MINIMUM_SIZE = 22
    const val ZIP_END_ENTRY_COUNT_OFFSET = 10
    const val ZIP_END_DIRECTORY_OFFSET = 16
    const val ZIP_CENTRAL_HEADER_SIZE = 46
    const val ZIP_CENTRAL_VERSION_PLATFORM_OFFSET = 5
    const val ZIP_CENTRAL_NAME_LENGTH_OFFSET = 28
    const val ZIP_CENTRAL_EXTRA_LENGTH_OFFSET = 30
    const val ZIP_CENTRAL_COMMENT_LENGTH_OFFSET = 32
    const val ZIP_CENTRAL_EXTERNAL_ATTRIBUTES_OFFSET = 38
    const val ZIP_UNIX_PLATFORM = 3
    const val PACKAGED_MANIFEST_PATH = "META-INF/revoman/performance/revup-v3-tree.json"
    const val EXPECTED_CONTENT_TYPE = "application/json; charset=utf-8"
    const val EXPECTED_TREE_SHA256 =
      "64e96abbfc128e858058530933e61a071497d45c1ab779702aff3635cd242e34"
    const val EXPECTED_CANONICAL_MANIFEST =
      "[{\"byteLength\":18,\"path\":\".resources/definition.yaml\",\"sha256\":\"3519d24f089597c00ee07fe71e20ac666046d3c8561e793f3d273667fbb7eaaa\"},{\"byteLength\":126,\"path\":\"benchmark.environment.yaml\",\"sha256\":\"a3e160c2397fb6472b84554742b6dded90c91231f200424d347a96ea5d02ebfd\"},{\"byteLength\":618,\"path\":\"benchmark.request.yaml\",\"sha256\":\"3ddc8e0db3f190f7244a57f91d76c15a34132768965da6c6cc9de1d4f5a23c39\"}]\n"
    const val DEFINITION_YAML = "\$kind: collection\n"
    const val ENVIRONMENT_YAML =
      "name: revup-v3-benchmark\nvalues:\n  - key: baseUrl\n    value: http://127.0.0.1:1\n  - key: markerSeed\n    value: fixture-marker\n"
    const val REQUEST_YAML =
      "\$kind: http-request\nname: benchmark\nurl: \"{{baseUrl}}/benchmark\"\nmethod: GET\nheaders:\n  X-Revoman-Marker: \"{{derivedMarker}}\"\nscripts:\n  - type: beforeRequest\n    code: |-\n      const seed = pm.environment.get(\"markerSeed\");\n      pm.environment.set(\"derivedMarker\", seed + \"-derived\");\n    language: text/javascript\n  - type: afterResponse\n    code: |-\n      const body = pm.response.json();\n      pm.test(\"benchmark response\", () => {\n        pm.expect(pm.response.code).to.eql(200);\n        pm.expect(body.id).to.eql(42);\n      });\n      pm.environment.set(\"id\", body.id);\n    language: text/javascript\norder: 1000\n"
  }
}
