/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.scenario

import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.log.RunLogSink
import com.salesforce.revoman.testing.http.MockHttpHandler
import com.salesforce.revoman.testing.http.MockHttpServer
import com.salesforce.revoman.testing.http.RecordedHttpRequest
import java.net.JarURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.jar.JarEntry
import java.util.jar.JarFile
import org.http4k.core.Body
import org.http4k.core.Method.GET
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK

internal data class RevUpV3FixtureIdentity(val canonicalJson: String, val sha256: String)

internal interface RevUpV3Server : AutoCloseable {
  val baseUrl: String

  fun requests(): List<RecordedHttpRequest>
}

internal data class RevUpV3ScenarioTestHooks(
  val fixtureRoot: Path? = null,
  val fixtureIdentity: RevUpV3FixtureIdentity? = null,
  val responseFactory: () -> Response = RevUpV3Scenario::expectedResponse,
  val serverFactory: (MockHttpHandler) -> RevUpV3Server = RevUpV3Scenario::startPublicServer,
  val afterServerStart: () -> Unit = {},
)

/** Trial-owned deterministic V3 real-wire scenario shared by the cold and warm JMH adapters. */
class RevUpV3Scenario
private constructor(
  private val server: RevUpV3Server,
  private val kick: Kick,
  private val handlerCount: AtomicLong,
  private val executeRevUp: (Kick) -> Rundown,
) : AutoCloseable {
  private val closeStarted = AtomicBoolean()
  private var verifiedInvocations = 0L
  private var pendingInvocation: PendingInvocation? = null

  fun execute(): Rundown {
    check(!closeStarted.get()) { "RevUp V3 scenario is closed" }
    check(pendingInvocation == null) { "previous RevUp V3 invocation is unverified" }
    val baseline = InvocationBaseline(server.requests().size.toLong(), handlerCount.get())
    return try {
      executeRevUp(kick).also { pendingInvocation = PendingInvocation(baseline, it) }
    } catch (failure: Throwable) {
      closeAfterFailure(failure)
    }
  }

  fun verifyInvocation() {
    val pending = checkNotNull(pendingInvocation) { "RevUp V3 invocation is missing" }
    try {
      verifyRundown(pending)
      verifiedInvocations += 1
      pendingInvocation = null
    } catch (failure: Throwable) {
      pendingInvocation = null
      closeAfterFailure(failure)
    }
  }

  override fun close() {
    if (!closeStarted.compareAndSet(false, true)) return
    var failure: Throwable? =
      runCatching {
          check(pendingInvocation == null) { "RevUp V3 scenario has an unverified invocation" }
          val requests = server.requests()
          check(requests.size.toLong() == verifiedInvocations) {
            "RevUp V3 retained request total does not match verified invocations"
          }
          check(handlerCount.get() == verifiedInvocations) {
            "RevUp V3 retained handler total does not match verified invocations"
          }
          requests.forEach(::verifyRecordedRequest)
        }
        .exceptionOrNull()
    try {
      server.close()
    } catch (closeFailure: Throwable) {
      if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
    }
    failure?.let { throw it }
  }

  private fun verifyRundown(pending: PendingInvocation) {
    val requestDelta = server.requests().size.toLong() - pending.baseline.requestCount
    val handlerDelta = handlerCount.get() - pending.baseline.handlerCount
    check(requestDelta == 1L) { "RevUp V3 invocation must record exactly one request" }
    check(handlerDelta == 1L) { "RevUp V3 invocation must match exactly one handler call" }

    val report = pending.rundown.stepReports.singleOrNull()
    check(report != null && report.isSuccessful && !report.isLedgerSkipped) {
      "RevUp V3 invocation must produce exactly one successful report"
    }
    val request = report.requestInfo?.takeIf { it.isRight }?.get()?.httpMsg
    check(request != null) { "RevUp V3 report must contain one successful request" }
    check(
      request.method == GET && request.uri.path == EXPECTED_PATH && request.uri.query.isEmpty()
    ) {
      "RevUp V3 report request must be exact GET $EXPECTED_PATH"
    }
    check(request.header(MARKER_HEADER) == EXPECTED_MARKER) {
      "RevUp V3 report request marker mismatch"
    }

    val response = report.responseInfo?.takeIf { it.isRight }?.get()?.httpMsg
    check(response != null && response.status == OK) { "RevUp V3 response status must be 200" }
    check(response.header("Content-Type") == EXPECTED_CONTENT_TYPE) {
      "RevUp V3 response content type mismatch"
    }
    check(response.bodyString().encodeToByteArray().contentEquals(EXPECTED_RESPONSE_BYTES)) {
      "RevUp V3 response bytes mismatch"
    }
    val assertion = report.pmTestAssertions.singleOrNull()
    check(assertion != null && assertion.passed && !assertion.skipped) {
      "RevUp V3 invocation must have one passing non-skipped pm.test"
    }
    check(pending.rundown.mutableEnv["id"] == EXPECTED_ID) {
      "RevUp V3 final environment id must be 42"
    }
  }

  private fun verifyRecordedRequest(request: RecordedHttpRequest) {
    check(request.method == GET && request.path == EXPECTED_PATH) {
      "RevUp V3 retained request must be exact GET $EXPECTED_PATH"
    }
    val markers =
      request.headers.filter { it.name.equals(MARKER_HEADER, ignoreCase = true) }.map { it.value }
    check(markers == listOf(EXPECTED_MARKER)) { "RevUp V3 retained request marker mismatch" }
    check(request.queryParameters.isEmpty() && request.bodyBytes().isEmpty()) {
      "RevUp V3 retained request must not contain query parameters or a body"
    }
  }

  private fun closeAfterFailure(primary: Throwable): Nothing {
    if (closeStarted.compareAndSet(false, true)) {
      try {
        server.close()
      } catch (closeFailure: Throwable) {
        primary.addSuppressed(closeFailure)
      }
    }
    throw primary
  }

  private data class InvocationBaseline(val requestCount: Long, val handlerCount: Long)

  private data class PendingInvocation(val baseline: InvocationBaseline, val rundown: Rundown)

  companion object {
    private const val RESOURCE_ROOT = "performance/revup-v3"
    private const val MANIFEST_PATH = "META-INF/revoman/performance/revup-v3-tree.json"
    private const val DEFINITION_PATH = ".resources/definition.yaml"
    private const val REQUEST_PATH = "benchmark.request.yaml"
    private const val ENVIRONMENT_PATH = "benchmark.environment.yaml"
    private const val EXPECTED_PATH = "/benchmark"
    private const val MARKER_HEADER = "X-Revoman-Marker"
    private const val EXPECTED_MARKER = "fixture-marker-derived"
    private const val EXPECTED_CONTENT_TYPE = "application/json; charset=utf-8"
    private const val EXPECTED_ID = 42
    private val EXPECTED_RESPONSE_BYTES = "{\"id\":42}".encodeToByteArray()
    private val EXPECTED_ENTRIES =
      listOf(
        FixtureEntry(
          DEFINITION_PATH,
          18,
          "3519d24f089597c00ee07fe71e20ac666046d3c8561e793f3d273667fbb7eaaa",
        ),
        FixtureEntry(
          ENVIRONMENT_PATH,
          126,
          "a3e160c2397fb6472b84554742b6dded90c91231f200424d347a96ea5d02ebfd",
        ),
        FixtureEntry(
          REQUEST_PATH,
          618,
          "3ddc8e0db3f190f7244a57f91d76c15a34132768965da6c6cc9de1d4f5a23c39",
        ),
      )
    private val EXPECTED_IDENTITY = identity(EXPECTED_ENTRIES)

    @JvmStatic fun start(): RevUpV3Scenario = startWith(RevUpV3ScenarioTestHooks())

    internal fun startForTest(hooks: RevUpV3ScenarioTestHooks): RevUpV3Scenario = startWith(hooks)

    internal fun packagedFixtureIdentityForTest(): RevUpV3FixtureIdentity =
      packagedFixture().identity

    internal fun fixtureIdentityForDirectoryForTest(root: Path): RevUpV3FixtureIdentity =
      identity(readDirectory(root))

    internal fun fixtureIdentityForArchiveForTest(archive: Path): RevUpV3FixtureIdentity =
      JarFile(archive.toFile()).use { jar ->
        verifiedPackagedIdentity(
          readArchive(jar, describeArchive(jar, Files.readAllBytes(archive)))
        )
      }

    internal fun verifyFixtureDirectoryForTest(root: Path) {
      requireIdentity(identity(readDirectory(root)), EXPECTED_IDENTITY)
    }

    internal fun expectedResponse(): Response =
      Response(OK)
        .header("Content-Type", EXPECTED_CONTENT_TYPE)
        .body(Body(ByteBuffer.wrap(EXPECTED_RESPONSE_BYTES)))

    internal fun startPublicServer(handler: MockHttpHandler): RevUpV3Server =
      MockHttpServer.start(handler).let { delegate ->
        object : RevUpV3Server {
          override val baseUrl: String = delegate.baseUrl

          override fun requests(): List<RecordedHttpRequest> = delegate.requests()

          override fun close() = delegate.close()
        }
      }

    private fun startWith(hooks: RevUpV3ScenarioTestHooks): RevUpV3Scenario {
      val fixture =
        hooks.fixtureRoot?.let { root ->
          val entries = readDirectory(root)
          requireIdentity(identity(entries), hooks.fixtureIdentity ?: EXPECTED_IDENTITY)
          ResolvedFixture(
            root.toAbsolutePath().normalize().toString(),
            root.resolve(ENVIRONMENT_PATH).toAbsolutePath().normalize().toString(),
            identity(entries),
          )
        } ?: packagedFixture()
      val handlerCount = AtomicLong()
      val handler = strictHandler(handlerCount, hooks.responseFactory)
      val server = hooks.serverFactory(handler)
      try {
        hooks.afterServerStart()
        val kick =
          Kick.configure()
            .templatePath(fixture.templatePath)
            .environmentPath(fixture.environmentPath)
            .dynamicEnvironment("baseUrl", server.baseUrl)
            .runLogSink(RunLogSink.NoOp)
            .off()
        return RevUpV3Scenario(server, kick, handlerCount, ReVoman::revUp)
      } catch (failure: Throwable) {
        try {
          server.close()
        } catch (closeFailure: Throwable) {
          failure.addSuppressed(closeFailure)
        }
        throw failure
      }
    }

    private fun strictHandler(
      count: AtomicLong,
      responseFactory: () -> Response,
    ): MockHttpHandler = MockHttpHandler { request ->
      check(
        request.method == GET && request.uri.path == EXPECTED_PATH && request.uri.query.isEmpty()
      ) {
        "Expected exact GET $EXPECTED_PATH"
      }
      check(request.header(MARKER_HEADER) == EXPECTED_MARKER) { "Expected derived wire marker" }
      check(request.bodyString().isEmpty()) { "Expected an empty request body" }
      count.incrementAndGet()
      responseFactory()
    }

    private fun packagedFixture(): ResolvedFixture {
      val classLoader =
        Thread.currentThread().contextClassLoader ?: RevUpV3Scenario::class.java.classLoader
      val definitionResources = classLoader.getResources("$RESOURCE_ROOT/$DEFINITION_PATH").toList()
      require(definitionResources.size == 1) {
        "Expected exactly one packaged RevUp V3 fixture, found ${definitionResources.size}"
      }
      val actual = verifiedPackagedIdentity(readPackaged(definitionResources.single()))
      requireIdentity(actual, EXPECTED_IDENTITY)
      return ResolvedFixture(
        RESOURCE_ROOT,
        "$RESOURCE_ROOT/$ENVIRONMENT_PATH",
        actual,
      )
    }

    private fun readPackaged(definition: URL): PackagedFixture =
      when (definition.protocol) {
        "file" -> {
          val fixtureRoot = Path.of(definition.toURI()).parent.parent
          val manifest = fixtureRoot.parent.parent.resolve(MANIFEST_PATH)
          require(
            Files.isRegularFile(manifest, NOFOLLOW_LINKS) && !Files.isSymbolicLink(manifest)
          ) {
            "Packaged RevUp V3 tree manifest must be one regular file"
          }
          PackagedFixture(readDirectory(fixtureRoot), Files.readAllBytes(manifest))
        }
        "jar" -> readJar(definition)
        else -> throw IllegalArgumentException("Unsupported packaged fixture URL: $definition")
      }

    private fun readJar(definition: URL): PackagedFixture {
      val connection = definition.openConnection() as JarURLConnection
      connection.useCaches = false
      val archiveBytes = connection.jarFileURL.openStream().use { it.readAllBytes() }
      connection.jarFile.use { jar ->
        return readArchive(jar, describeArchive(jar, archiveBytes))
      }
    }

    private fun readArchive(jar: JarFile, archive: List<ArchiveEntry>): PackagedFixture {
      val fixtureEntries = readArchiveFixtureEntries(jar, archive)
      val manifestEntries = archive.filter { it.name == MANIFEST_PATH }
      require(manifestEntries.size == 1) {
        "Expected exactly one packaged RevUp V3 tree manifest, found ${manifestEntries.size}"
      }
      val manifest = manifestEntries.single()
      require(manifest.kind == ArchiveEntryKind.REGULAR) {
        "Packaged RevUp V3 tree manifest must be one regular file"
      }
      return PackagedFixture(
        fixtureEntries,
        jar.getInputStream(manifest.jarEntry).use { it.readAllBytes() },
      )
    }

    private fun verifiedPackagedIdentity(packaged: PackagedFixture): RevUpV3FixtureIdentity {
      val actual = identity(packaged.fixtureEntries)
      require(packaged.manifestBytes.contentEquals(actual.canonicalJson.toByteArray(UTF_8))) {
        "Packaged RevUp V3 tree manifest does not match verified fixture bytes"
      }
      return actual
    }

    private fun describeArchive(jar: JarFile, archiveBytes: ByteArray): List<ArchiveEntry> {
      val jarEntries = jar.entries().asSequence().toList()
      val centralEntries = readCentralDirectory(archiveBytes)
      require(jarEntries.size == centralEntries.size) {
        "Packaged RevUp V3 archive entry metadata is inconsistent"
      }
      return jarEntries.zip(centralEntries) { jarEntry, centralEntry ->
        require(jarEntry.name == centralEntry.name) {
          "Packaged RevUp V3 archive entry names are inconsistent"
        }
        ArchiveEntry(jarEntry, centralEntry.name, centralEntry.kind)
      }
    }

    private fun readArchiveFixtureEntries(
      jar: JarFile,
      archive: List<ArchiveEntry>,
    ): List<FixtureEntry> {
      val prefix = "$RESOURCE_ROOT/"
      val fixtureEntries = archive.filter { it.name.startsWith(prefix) }
      val symlinks = fixtureEntries.filter { it.kind == ArchiveEntryKind.SYMBOLIC_LINK }
      require(symlinks.isEmpty()) {
        "Packaged RevUp V3 fixture contains a symbolic link: ${symlinks.map(ArchiveEntry::name)}"
      }
      val nonRegular = fixtureEntries.filter {
        it.kind == ArchiveEntryKind.OTHER ||
          (it.kind == ArchiveEntryKind.DIRECTORY &&
            it.name.removePrefix(prefix) !in setOf("", ".resources/"))
      }
      require(nonRegular.isEmpty()) {
        "Packaged RevUp V3 fixture contains a non-regular entry: ${nonRegular.map(ArchiveEntry::name)}"
      }
      val regularEntries = fixtureEntries.filter { it.kind == ArchiveEntryKind.REGULAR }
      val relativeEntries = regularEntries.groupBy { it.name.removePrefix(prefix) }
      val duplicates = relativeEntries.filterValues { it.size != 1 }.keys
      require(duplicates.isEmpty()) {
        "Packaged RevUp V3 fixture contains duplicate entries: ${duplicates.sorted()}"
      }
      val expectedPaths = EXPECTED_ENTRIES.map(FixtureEntry::path).toSet()
      require(relativeEntries.keys == expectedPaths) {
        "Packaged RevUp V3 fixture contains unexpected or missing files: ${relativeEntries.keys.sorted()}"
      }
      return relativeEntries
        .map { (relative, entries) ->
          val bytes = jar.getInputStream(entries.single().jarEntry).use { it.readAllBytes() }
          FixtureEntry(relative, bytes.size.toLong(), sha256(bytes))
        }
        .sortedWith { left, right -> utf8Compare(left.path, right.path) }
    }

    private fun readCentralDirectory(bytes: ByteArray): List<CentralEntry> {
      require(bytes.size >= ZIP_END_MINIMUM_SIZE) { "Packaged RevUp V3 archive is truncated" }
      val lowerBound = maxOf(0, bytes.size - ZIP_END_MINIMUM_SIZE - ZIP_MAX_COMMENT_SIZE)
      val end =
        (bytes.size - ZIP_END_MINIMUM_SIZE downTo lowerBound).firstOrNull { offset ->
          readUnsignedInt(bytes, offset) == ZIP_END_SIGNATURE
        } ?: throw IllegalArgumentException("Packaged RevUp V3 archive has no end record")
      val entryCount = readUnsignedShort(bytes, end + ZIP_END_ENTRY_COUNT_OFFSET)
      val directorySize = readUnsignedInt(bytes, end + ZIP_END_DIRECTORY_SIZE_OFFSET)
      val directoryOffset = readUnsignedInt(bytes, end + ZIP_END_DIRECTORY_OFFSET)
      require(
        entryCount != ZIP64_ENTRY_COUNT &&
          directorySize != ZIP64_UNSIGNED_INT &&
          directoryOffset != ZIP64_UNSIGNED_INT
      ) {
        "ZIP64 RevUp V3 archives are unsupported"
      }
      require(directoryOffset + directorySize <= end.toLong()) {
        "Packaged RevUp V3 central directory is outside the archive"
      }
      var cursor = directoryOffset.toInt()
      return buildList(entryCount) {
        repeat(entryCount) {
          require(readUnsignedInt(bytes, cursor) == ZIP_CENTRAL_SIGNATURE) {
            "Packaged RevUp V3 central directory is malformed"
          }
          val nameLength = readUnsignedShort(bytes, cursor + ZIP_CENTRAL_NAME_LENGTH_OFFSET)
          val extraLength = readUnsignedShort(bytes, cursor + ZIP_CENTRAL_EXTRA_LENGTH_OFFSET)
          val commentLength = readUnsignedShort(bytes, cursor + ZIP_CENTRAL_COMMENT_LENGTH_OFFSET)
          val nameStart = cursor + ZIP_CENTRAL_HEADER_SIZE
          val entryEnd = nameStart + nameLength + extraLength + commentLength
          require(entryEnd <= bytes.size) {
            "Packaged RevUp V3 central directory entry is truncated"
          }
          val name = bytes.copyOfRange(nameStart, nameStart + nameLength).toString(UTF_8)
          val platform = bytes[cursor + ZIP_CENTRAL_VERSION_PLATFORM_OFFSET].toInt() and 0xff
          val externalAttributes =
            readUnsignedInt(bytes, cursor + ZIP_CENTRAL_EXTERNAL_ATTRIBUTES_OFFSET)
          add(CentralEntry(name, archiveEntryKind(name, platform, externalAttributes)))
          cursor = entryEnd
        }
        require(cursor.toLong() == directoryOffset + directorySize) {
          "Packaged RevUp V3 central directory size is inconsistent"
        }
      }
    }

    private fun archiveEntryKind(
      name: String,
      platform: Int,
      externalAttributes: Long,
    ): ArchiveEntryKind {
      if (name.endsWith('/')) return ArchiveEntryKind.DIRECTORY
      if (platform != ZIP_UNIX_PLATFORM) return ArchiveEntryKind.REGULAR
      return when (((externalAttributes ushr 16).toInt()) and UNIX_FILE_TYPE_MASK) {
        0,
        UNIX_REGULAR_FILE -> ArchiveEntryKind.REGULAR
        UNIX_DIRECTORY -> ArchiveEntryKind.DIRECTORY
        UNIX_SYMBOLIC_LINK -> ArchiveEntryKind.SYMBOLIC_LINK
        else -> ArchiveEntryKind.OTHER
      }
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int =
      (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long =
      (bytes[offset].toLong() and 0xff) or
        ((bytes[offset + 1].toLong() and 0xff) shl 8) or
        ((bytes[offset + 2].toLong() and 0xff) shl 16) or
        ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun readDirectory(requestedRoot: Path): List<FixtureEntry> {
      val root = requestedRoot.toAbsolutePath().normalize()
      require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
        "RevUp V3 fixture root must be a real directory"
      }
      val paths = Files.walk(root).use { it.toList() }
      require(paths.none(Files::isSymbolicLink)) {
        "RevUp V3 fixture cannot contain a symbolic link"
      }
      require(
        paths.all { path ->
          path == root ||
            Files.isDirectory(path, NOFOLLOW_LINKS) ||
            Files.isRegularFile(path, NOFOLLOW_LINKS)
        }
      ) {
        "RevUp V3 fixture contains a non-regular file"
      }
      val files =
        paths
          .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
          .associateBy { portable(root.relativize(it)) }
      val expectedPaths = EXPECTED_ENTRIES.map(FixtureEntry::path).toSet()
      require(files.keys == expectedPaths) {
        "RevUp V3 fixture contains unexpected or missing files: ${files.keys.sorted()}"
      }
      return files
        .map { (relative, path) ->
          val bytes = Files.readAllBytes(path)
          FixtureEntry(relative, bytes.size.toLong(), sha256(bytes))
        }
        .sortedWith { left, right -> utf8Compare(left.path, right.path) }
    }

    private fun requireIdentity(
      actual: RevUpV3FixtureIdentity,
      expected: RevUpV3FixtureIdentity,
    ) {
      require(actual == expected) {
        "RevUp V3 fixture identity mismatch: expected ${expected.sha256}, got ${actual.sha256}"
      }
    }

    private fun identity(entries: List<FixtureEntry>): RevUpV3FixtureIdentity {
      val canonical =
        entries
          .sortedWith { left, right -> utf8Compare(left.path, right.path) }
          .joinToString(separator = ",", prefix = "[", postfix = "]\n") { entry ->
            "{\"byteLength\":${entry.byteLength},\"path\":\"${entry.path}\",\"sha256\":\"${entry.sha256}\"}"
          }
      return RevUpV3FixtureIdentity(canonical, sha256(canonical.toByteArray(UTF_8)))
    }

    private fun utf8Compare(left: String, right: String): Int {
      val leftBytes = left.toByteArray(UTF_8)
      val rightBytes = right.toByteArray(UTF_8)
      val common = minOf(leftBytes.size, rightBytes.size)
      for (index in 0 until common) {
        val comparison =
          (leftBytes[index].toInt() and 0xff).compareTo(rightBytes[index].toInt() and 0xff)
        if (comparison != 0) return comparison
      }
      return leftBytes.size.compareTo(rightBytes.size)
    }

    private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") {
        "%02x".format(it.toInt() and 0xff)
      }

    private fun portable(path: Path): String = path.joinToString("/") { it.toString() }

    private data class FixtureEntry(val path: String, val byteLength: Long, val sha256: String)

    private data class PackagedFixture(
      val fixtureEntries: List<FixtureEntry>,
      val manifestBytes: ByteArray,
    )

    private data class ArchiveEntry(
      val jarEntry: JarEntry,
      val name: String,
      val kind: ArchiveEntryKind,
    )

    private data class CentralEntry(val name: String, val kind: ArchiveEntryKind)

    private enum class ArchiveEntryKind {
      REGULAR,
      DIRECTORY,
      SYMBOLIC_LINK,
      OTHER,
    }

    private data class ResolvedFixture(
      val templatePath: String,
      val environmentPath: String,
      val identity: RevUpV3FixtureIdentity,
    )

    private const val ZIP_END_SIGNATURE = 0x06054b50L
    private const val ZIP_CENTRAL_SIGNATURE = 0x02014b50L
    private const val ZIP_END_MINIMUM_SIZE = 22
    private const val ZIP_MAX_COMMENT_SIZE = 65_535
    private const val ZIP_END_ENTRY_COUNT_OFFSET = 10
    private const val ZIP_END_DIRECTORY_SIZE_OFFSET = 12
    private const val ZIP_END_DIRECTORY_OFFSET = 16
    private const val ZIP_CENTRAL_HEADER_SIZE = 46
    private const val ZIP_CENTRAL_VERSION_PLATFORM_OFFSET = 5
    private const val ZIP_CENTRAL_NAME_LENGTH_OFFSET = 28
    private const val ZIP_CENTRAL_EXTRA_LENGTH_OFFSET = 30
    private const val ZIP_CENTRAL_COMMENT_LENGTH_OFFSET = 32
    private const val ZIP_CENTRAL_EXTERNAL_ATTRIBUTES_OFFSET = 38
    private const val ZIP_UNIX_PLATFORM = 3
    private const val ZIP64_ENTRY_COUNT = 0xffff
    private const val ZIP64_UNSIGNED_INT = 0xffffffffL
    private const val UNIX_FILE_TYPE_MASK = 0xf000
    private const val UNIX_REGULAR_FILE = 0x8000
    private const val UNIX_DIRECTORY = 0x4000
    private const val UNIX_SYMBOLIC_LINK = 0xa000
  }
}
