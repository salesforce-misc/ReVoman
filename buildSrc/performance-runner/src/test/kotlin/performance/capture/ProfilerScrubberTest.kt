/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import jdk.jfr.Event
import jdk.jfr.Name
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind

class ProfilerScrubberTest :
  FunSpec(
    {
      test("scrubber persists a schema-valid summary and intent before deleting raw JFR") {
        val root = Files.createTempDirectory("profiler-scrub-")
        try {
          val raw = root.resolve("profiler.jfr")
          writeRecording(raw)
          val rawHash = Sha256.digest(Files.readAllBytes(raw))
          val request = request(root, raw)

          val outcome =
            ProfilerScrubber().scrub(request).shouldBeInstanceOf<ProfilerScrubOutcome.Completed>()

          outcome.summary.rawInputSha256 shouldBe rawHash
          Files.exists(raw) shouldBe false
          Files.isRegularFile(request.summaryPath) shouldBe true
          Files.isRegularFile(request.intentPath) shouldBe true
          Files.isRegularFile(request.completionPath) shouldBe true
          val summaryBytes = Files.readAllBytes(request.summaryPath)
          CanonicalJson.encode(CanonicalJson.parseStrict(summaryBytes)) shouldBe summaryBytes
          EvidenceSchemaValidator()
            .validate(SchemaKind.PROFILER_SUMMARY, summaryBytes)
            .isEmpty() shouldBe true
          val publicText =
            listOf(request.summaryPath, request.intentPath, request.completionPath)
              .joinToString("\n") { Files.readString(it) }
          publicText shouldNotContain root.toString()
          publicText shouldNotContain System.getProperty("user.name")
          publicText shouldNotContain "thread"
          publicText shouldNotContain "command"
          publicText shouldNotContain "environment"
          publicText shouldNotContain "systemProperties"
        } finally {
          root.toFile().deleteRecursively()
        }
      }

      test("invalid JFR is retained and no transaction marker is published") {
        val root = Files.createTempDirectory("profiler-scrub-invalid-")
        try {
          val raw = root.resolve("profiler.jfr")
          Files.writeString(raw, "not a recording")
          val request = request(root, raw)

          val outcome =
            ProfilerScrubber().scrub(request).shouldBeInstanceOf<ProfilerScrubOutcome.Invalid>()

          outcome.reasons.isEmpty() shouldBe false
          Files.readString(raw) shouldBe "not a recording"
          Files.exists(request.summaryPath) shouldBe false
          Files.exists(request.intentPath) shouldBe false
          Files.exists(request.completionPath) shouldBe false
        } finally {
          root.toFile().deleteRecursively()
        }
      }

      test("scrubber accepts one raw recording assembled from sequential fork recordings") {
        val root = Files.createTempDirectory("profiler-scrub-forks-")
        try {
          val first = root.resolve("fork-1.jfr")
          val second = root.resolve("fork-2.jfr")
          val raw = root.resolve("profiler.jfr")
          writeRecording(first, "first")
          writeRecording(second, "second")
          Files.newOutputStream(raw).use { output ->
            Files.copy(first, output)
            Files.copy(second, output)
          }
          Files.delete(first)
          Files.delete(second)
          RecordingFile.readAllEvents(raw)
            .count { it.eventType.name == "performance.capture.TestProfilerEvent" } shouldBe 2

          ProfilerScrubber()
            .scrub(request(root, raw))
            .shouldBeInstanceOf<ProfilerScrubOutcome.Completed>()

          Files.exists(raw) shouldBe false
        } finally {
          root.toFile().deleteRecursively()
        }
      }

      test("raw input hash mismatch fails closed and retains the recording") {
        val root = Files.createTempDirectory("profiler-scrub-hash-")
        try {
          val raw = root.resolve("profiler.jfr")
          writeRecording(raw)
          val request = request(root, raw).copy(expectedRawInputSha256 = Sha256.parse(TEST_SHA))

          val outcome =
            ProfilerScrubber().scrub(request).shouldBeInstanceOf<ProfilerScrubOutcome.Invalid>()

          outcome.reasons shouldBe listOf(ProfilerScrubFailure.RAW_INPUT_HASH_MISMATCH)
          Files.exists(raw) shouldBe true
        } finally {
          root.toFile().deleteRecursively()
        }
      }

      test("raw recording replacement after verification fails closed before parsing") {
        val root = Files.createTempDirectory("profiler-scrub-race-")
        try {
          val raw = root.resolve("profiler.jfr")
          val replacement = root.resolve("replacement.jfr")
          val original = root.resolve("original.jfr")
          writeRecording(raw, "original")
          writeRecording(replacement, "replacement")
          val request = request(root, raw)
          val scrubber =
            ProfilerScrubber(
              hooks =
                ProfilerScrubHooks(
                  beforeBinding = {
                    Files.move(raw, original)
                    Files.move(replacement, raw, REPLACE_EXISTING)
                  },
                ),
            )

          val outcome = scrubber.scrub(request).shouldBeInstanceOf<ProfilerScrubOutcome.Invalid>()

          outcome.reasons shouldBe listOf(ProfilerScrubFailure.RAW_INPUT_HASH_MISMATCH)
          Files.exists(raw) shouldBe true
          Files.exists(original) shouldBe true
          Files.exists(request.summaryPath) shouldBe false
          Files.exists(request.intentPath) shouldBe false
          Files.exists(request.completionPath) shouldBe false
        } finally {
          root.toFile().deleteRecursively()
        }
      }

      test("bound recording symlink replacement before open fails closed") {
        val root = Files.createTempDirectory("profiler-scrub-bound-race-")
        try {
          val raw = root.resolve("profiler.jfr")
          val replacement = root.resolve("replacement.jfr")
          writeRecording(raw, "original")
          writeRecording(replacement, "replacement")
          val request = request(root, raw)
          val scrubber =
            ProfilerScrubber(
              hooks =
                ProfilerScrubHooks(
                  afterBindingBeforeOpen = { boundPath ->
                    Files.delete(boundPath)
                    Files.createSymbolicLink(boundPath, replacement)
                  },
                ),
            )

          val outcome = scrubber.scrub(request).shouldBeInstanceOf<ProfilerScrubOutcome.Invalid>()

          outcome.reasons shouldBe listOf(ProfilerScrubFailure.RAW_INPUT_HASH_MISMATCH)
          Files.exists(raw) shouldBe true
          Files.exists(request.summaryPath) shouldBe false
          Files.exists(request.intentPath) shouldBe false
          Files.exists(request.completionPath) shouldBe false
        } finally {
          root.toFile().deleteRecursively()
        }
      }

      test("raw replacement after durable intent is retained and fails before deletion") {
        val root = Files.createTempDirectory("profiler-scrub-delete-race-")
        try {
          val raw = root.resolve("profiler.jfr")
          val original = root.resolve("original.jfr")
          val replacement = root.resolve("replacement.jfr")
          writeRecording(raw, "original")
          writeRecording(replacement, "replacement")
          val replacementHash = Sha256.digest(replacement)
          val request = request(root, raw)
          val scrubber =
            ProfilerScrubber(
              hooks =
                ProfilerScrubHooks(
                  beforeRawRetirement = {
                    Files.move(raw, original)
                    Files.move(replacement, raw)
                  },
                ),
            )

          val outcome = scrubber.scrub(request).shouldBeInstanceOf<ProfilerScrubOutcome.Invalid>()

          outcome.reasons shouldBe listOf(ProfilerScrubFailure.RAW_INPUT_HASH_MISMATCH)
          Files.exists(original) shouldBe true
          Files.exists(raw) shouldBe true
          Sha256.digest(raw) shouldBe replacementHash
          Files.exists(request.summaryPath) shouldBe true
          Files.exists(request.intentPath) shouldBe true
          Files.exists(request.completionPath) shouldBe false
        } finally {
          root.toFile().deleteRecursively()
        }
      }

      test("failure before durable intent cleans owned scratch and permits retry") {
        val root = Files.createTempDirectory("profiler-scrub-retry-")
        try {
          val raw = root.resolve("profiler.jfr")
          writeRecording(raw)
          val request = request(root, raw)
          val scrubber =
            ProfilerScrubber(
              hooks =
                ProfilerScrubHooks(
                  afterSummaryBeforeIntent = { error("injected pre-intent failure") },
                ),
            )

          val first = scrubber.scrub(request).shouldBeInstanceOf<ProfilerScrubOutcome.Invalid>()

          first.reasons shouldBe listOf(ProfilerScrubFailure.TRANSACTION_FAILED)
          Files.exists(raw) shouldBe true
          Files.exists(root.resolve(".profiler-scrub-input.open")) shouldBe false
          Files.exists(root.resolve(".profiler-summary.json.tmp")) shouldBe false
          Files.exists(request.summaryPath) shouldBe false
          Files.exists(request.intentPath) shouldBe false
          Files.exists(request.completionPath) shouldBe false

          ProfilerScrubber().scrub(request).shouldBeInstanceOf<ProfilerScrubOutcome.Completed>()
        } finally {
          root.toFile().deleteRecursively()
        }
      }

      test("non-normalized transaction paths are rejected without touching raw input") {
        val root = Files.createTempDirectory("profiler-scrub-path-")
        try {
          val raw = root.resolve("profiler.jfr")
          writeRecording(raw)
          val request = request(root, raw).copy(rawPath = root.resolve("nested/../profiler.jfr"))

          val outcome =
            ProfilerScrubber().scrub(request).shouldBeInstanceOf<ProfilerScrubOutcome.Invalid>()

          outcome.reasons shouldBe listOf(ProfilerScrubFailure.INVALID_PATHS)
          Files.exists(raw) shouldBe true
          Files.exists(request.summaryPath) shouldBe false
        } finally {
          root.toFile().deleteRecursively()
        }
      }
    },
  )

private fun request(root: java.nio.file.Path, raw: java.nio.file.Path): ProfilerScrubRequest =
  ProfilerScrubRequest(
    captureId = "capture-1",
    provisionalCaptureSha256 = Sha256.parse("c".repeat(64)),
    expectedRawInputSha256 = Sha256.digest(Files.readAllBytes(raw)),
    variantSha256 = Sha256.parse("b".repeat(64)),
    settingsSha256 = Sha256.parse(TEST_SHA),
    rawPath = raw,
    summaryPath = root.resolve("profiler-summary.json"),
    intentPath = root.resolve("profiler-scrub.intent.json"),
    completionPath = root.resolve("profiler-scrub.complete.json"),
  )

private fun writeRecording(path: java.nio.file.Path, message: String = "safe") {
  Recording().use { recording ->
    recording.enable(TestProfilerEvent::class.java)
    recording.start()
    TestProfilerEvent().apply { this.message = message }.commit()
    recording.stop()
    recording.dump(path)
  }
}

@Name("performance.capture.TestProfilerEvent")
private class TestProfilerEvent : Event() {
  var message: String = ""
}
