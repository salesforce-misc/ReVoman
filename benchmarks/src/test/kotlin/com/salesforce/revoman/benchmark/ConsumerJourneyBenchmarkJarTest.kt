package com.salesforce.revoman.benchmark

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipFile

class ConsumerJourneyBenchmarkJarTest :
  StringSpec({
    "executable benchmark jar preserves its multi-release manifest" {
      val jar = benchmarkJar()

      ZipFile(jar.toFile()).use { archive ->
        val manifest =
          archive
            .getInputStream(archive.getEntry("META-INF/MANIFEST.MF"))
            .use(::Manifest)
            .mainAttributes
        val containsVersionedClasses =
          archive.entries().asSequence().any { entry ->
            !entry.isDirectory && entry.name.startsWith("META-INF/versions/")
          }

        manifest.getValue(Attributes.Name.MAIN_CLASS) shouldBe "org.openjdk.jmh.Main"
        if (containsVersionedClasses) {
          manifest.getValue(Attributes.Name.MULTI_RELEASE) shouldBe "true"
        }
      }
    }

    "executable benchmark jar completes the scripted consumer journey" {
      val outputFile = Files.createTempFile("consumer-journey-scripted-jmh-", ".log")
      try {
        val process =
          ProcessBuilder(
              javaExecutable().toString(),
              "-jar",
              benchmarkJar().toString(),
              SCRIPTED_BENCHMARK_SELECTOR,
              "-bm",
              "avgt",
              "-tu",
              "ms",
              "-t",
              "1",
              "-f",
              "1",
              "-wi",
              "0",
              "-i",
              "1",
              "-r",
              "1ms",
              "-jvmArgsAppend",
              "-Drevoman.scorecard.expectedJavaFeature=25 -Drevoman.banner=off",
            )
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .start()

        val completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
          process.destroyForcibly()
          process.waitFor()
        }
        completed shouldBe true

        val output = Files.readString(outputFile)
        process.exitValue() shouldBe 0
        output shouldContain "# Run complete."
        output shouldContain
          "Result \"com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.v3TenStepScriptedRevUp\""
        output shouldNotContain "Exception while executing"
        output shouldNotContain "Truffle could not be initialized"
        output shouldNotContain "org.graalvm.polyglot"
      } finally {
        Files.deleteIfExists(outputFile)
      }
    }
  })

private const val PROCESS_TIMEOUT_SECONDS = 60L
private const val SCRIPTED_BENCHMARK_SELECTOR =
  "^com\\.salesforce\\.revoman\\.benchmark\\.ConsumerJourneyBenchmark\\.v3TenStepScriptedRevUp$"

private fun benchmarkJar(): Path =
  Path.of("build/benchmarks/main/jars/benchmarks-main-jmh-JMH.jar").toAbsolutePath().also {
    it.shouldExist()
  }

private fun javaExecutable(): Path =
  Path.of(System.getProperty("java.home"), "bin", "java").also { it.shouldExist() }
