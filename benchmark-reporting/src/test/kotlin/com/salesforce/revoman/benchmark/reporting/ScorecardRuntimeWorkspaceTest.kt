package com.salesforce.revoman.benchmark.reporting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import jdk.jfr.Configuration

class ScorecardRuntimeWorkspaceTest :
  StringSpec({
    "runtime workspace writes a valid opt-in configuration for only the semantic events" {
      val source = Files.createTempFile("scorecard-runtime-source-", ".jar")
      Files.writeString(source, "benchmark")
      val workspace = createScorecardRuntimeWorkspace(source)
      try {
        val configuration = Configuration.create(workspace.performanceJfrConfiguration)
        val enabled =
          configuration.settings
            .filterValues { value -> value == "true" }
            .keys
            .map { setting -> setting.substringBefore('#') }

        enabled shouldContainExactlyInAnyOrder
          listOf(
            "com.salesforce.revoman.performance.Operation",
            "com.salesforce.revoman.performance.Kick",
            "com.salesforce.revoman.performance.Step",
          )
        configuration.settings
          .filterKeys { setting -> setting.endsWith("#stackTrace") }
          .values
          .toSet() shouldBe setOf("false")
      } finally {
        deleteScorecardRuntimeWorkspace(workspace.root)
        Files.deleteIfExists(source)
      }
    }

    "recording discovery accepts one nested JMH result and rejects ambiguity" {
      val root = Files.createTempDirectory("scorecard-recording-discovery-")
      try {
        val first = root.resolve("benchmark-a/profile.jfr")
        Files.createDirectories(first.parent)
        Files.writeString(first, "first")

        findJfrProfilerRecording(root) shouldBe first.toAbsolutePath().normalize()

        val second = root.resolve("benchmark-b/profile.jfr")
        Files.createDirectories(second.parent)
        Files.writeString(second, "second")
        shouldThrow<IllegalArgumentException> { findJfrProfilerRecording(root) }
          .message shouldContain "multiple"
      } finally {
        deleteScorecardRuntimeWorkspace(root)
      }
    }
  })
