package com.salesforce.revoman.benchmark.reporting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path

class ScorecardCommandsTest :
  StringSpec({
    "profile command uses JMH's native async-profiler integration with ReVoman JFR events" {
      val workspace =
        ScorecardRuntimeWorkspace(
          root = Path.of("/runtime"),
          benchmarkJar = Path.of("/runtime/benchmark.jar"),
          userHome = Path.of("/runtime/home"),
          temporaryDirectory = Path.of("/runtime/tmp"),
        )
      val outputDirectory = Path.of("/runtime/profiles/v3TenStepRevUp/cpu")

      val command =
        profileCommand(
          preflight = preflight,
          affinity =
            CpuAffinity(
              logicalCpus = listOf(0, 2),
              logicalCpuList = "0,2",
              allowedCpus = setOf(0, 1, 2, 3),
              onlineCpus = setOf(0, 1, 2, 3),
              siblingGroups = listOf(setOf(0, 1), setOf(2, 3)),
            ),
          workspace = workspace,
          method = "v3TenStepRevUp",
          event = "cpu",
          profilerLibrary = Path.of("/jdk/lib/libasyncProfiler.so"),
          outputDirectory = outputDirectory,
        )

      command.drop(command.indexOf("-prof")) shouldContainExactly
        listOf(
          "-prof",
          "async:libPath=/jdk/lib/libasyncProfiler.so;output=jfr;event=cpu;" +
            "dir=$outputDirectory/samples",
          "-prof",
          "jfr:dir=$outputDirectory/semantic;" +
            "configName=/runtime/revoman-performance.jfc;debugNonSafePoints=false",
          "-jvmArgsAppend",
          command.last(),
        )
      command.last() shouldNotContain "-agentpath"
      command.last() shouldBe
        "-Drevoman.scorecard.expectedJavaFeature=25 -Drevoman.banner=off " +
          "-Duser.name=revoman-scorecard -Duser.home=/runtime/home -Duser.dir=/runtime " +
          "-Djava.io.tmpdir=/runtime/tmp"
    }

    "wall profiling pins the interval used by attribution weights" {
      val workspace =
        ScorecardRuntimeWorkspace(
          root = Path.of("/runtime"),
          benchmarkJar = Path.of("/runtime/benchmark.jar"),
          userHome = Path.of("/runtime/home"),
          temporaryDirectory = Path.of("/runtime/tmp"),
        )

      val command =
        profileCommand(
          preflight = preflight,
          affinity =
            CpuAffinity(
              logicalCpus = listOf(0, 2),
              logicalCpuList = "0,2",
              allowedCpus = setOf(0, 1, 2, 3),
              onlineCpus = setOf(0, 1, 2, 3),
              siblingGroups = listOf(setOf(0, 1), setOf(2, 3)),
            ),
          workspace = workspace,
          method = "v3TenStepRevUp",
          event = "wall",
          profilerLibrary = Path.of("/jdk/lib/libasyncProfiler.so"),
          outputDirectory = Path.of("/runtime/profiles/v3TenStepRevUp/wall"),
        )

      command.single { it.startsWith("async:") } shouldBe
        "async:libPath=/jdk/lib/libasyncProfiler.so;output=jfr;event=wall;" +
          "interval=$WALL_SAMPLE_INTERVAL_NS;" +
          "dir=/runtime/profiles/v3TenStepRevUp/wall/samples"
    }
  }) {
  private companion object {
    val preflight =
      ScorecardPreflight(
        projectRoot = Path.of("/project"),
        benchmarkJar = Path.of("/runtime/benchmark.jar"),
        javaExecutable = Path.of("/jdk/bin/java"),
        libraryVersion = "test",
        revision = "0".repeat(40),
        allowedDirtyPaths = emptySet(),
        javaIdentities = emptyMap(),
        runtimeValidation = RuntimeValidation("", "", emptyList(), "", "", emptyMap()),
      )
  }
}
