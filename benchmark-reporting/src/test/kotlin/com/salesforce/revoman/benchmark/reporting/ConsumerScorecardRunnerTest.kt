package com.salesforce.revoman.benchmark.reporting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ConsumerScorecardRunnerTest :
  StringSpec({
    "preflight independently rejects every Java layer other than 25" {
      withRunnerFixture { fixture ->
        listOf(
            "runner" to fixture.host.copy(runnerJavaFeature = 21),
            "launcher" to fixture.host.copy(launcherJavaFeature = 21),
            "inherited" to fixture.host.copy(inheritedJavaFeature = 21),
          )
          .forEach { (layer, host) ->
            shouldThrow<IllegalArgumentException> {
                ConsumerScorecardRunner(host, fixture.executor).preflight(fixture.request)
              }
              .message shouldContain layer
          }

        listOf(
            "Gradle daemon" to fixture.request.copy(gradleDaemonJavaFeature = 21),
            "JMH" to fixture.request.copy(javaFeature = 21),
          )
          .forEach { (layer, request) ->
            shouldThrow<IllegalArgumentException> {
                ConsumerScorecardRunner(fixture.host, fixture.executor).preflight(request)
              }
              .message shouldContain layer
          }
      }
    }

    "preflight requires a single Gradle worker" {
      withRunnerFixture { fixture ->
        shouldThrow<IllegalArgumentException> {
            ConsumerScorecardRunner(fixture.host, fixture.executor)
              .preflight(fixture.request.copy(gradleMaxWorkers = 2))
          }
          .message shouldContain "one worker"
      }
    }

    "preflight rejects an unreadable or non-executable Java launcher" {
      withRunnerFixture { fixture ->
        listOf(
            "readable" to fixture.host.copy(unreadable = setOf(fixture.request.javaExecutable)),
            "executable" to
              fixture.host.copy(nonExecutable = setOf(fixture.request.javaExecutable)),
          )
          .forEach { (expected, host) ->
            shouldThrow<IllegalArgumentException> {
                ConsumerScorecardRunner(host, fixture.executor).preflight(fixture.request)
              }
              .message shouldContain expected
          }
      }
    }

    "preflight rejects an unreadable or non-JMH benchmark jar" {
      withRunnerFixture { fixture ->
        shouldThrow<IllegalArgumentException> {
            ConsumerScorecardRunner(
                fixture.host.copy(unreadable = setOf(fixture.request.benchmarkJar)),
                fixture.executor,
              )
              .preflight(fixture.request)
          }
          .message shouldContain "readable"

        Files.writeString(fixture.request.benchmarkJar, "not a jar")
        shouldThrow<IllegalArgumentException> {
            ConsumerScorecardRunner(fixture.host, fixture.executor).preflight(fixture.request)
          }
          .message shouldContain "JMH"
      }
    }

    "preflight rejects runtime validation from another revision" {
      withRunnerFixture { fixture ->
        fixture.request.runtimeValidation.writeText(
          validRuntimeValidation("ffffffffffffffffffffffffffffffffffffffff")
        )

        shouldThrow<IllegalArgumentException> {
            ConsumerScorecardRunner(fixture.host, fixture.executor).preflight(fixture.request)
          }
          .message shouldContain "revision"
      }
    }

    "preflight accepts only explicitly allowed normalized dirty paths" {
      withRunnerFixture { fixture ->
        val dirtyHost = fixture.host.copy(gitStatus = " M .idea/misc.xml\u0000")
        ConsumerScorecardRunner(dirtyHost, fixture.executor)
          .preflight(
            fixture.request.copy(allowedDirtyPaths = setOf(Path.of(".idea/../.idea/misc.xml")))
          )

        shouldThrow<IllegalArgumentException> {
            ConsumerScorecardRunner(dirtyHost, fixture.executor)
              .preflight(fixture.request.copy(allowedDirtyPaths = emptySet()))
          }
          .message shouldContain ".idea/misc.xml"
      }
    }

    "preflight validates both paths in a porcelain rename record" {
      withRunnerFixture { fixture ->
        val renamedHost = fixture.host.copy(gitStatus = "R  renamed.txt\u0000original.txt\u0000")

        ConsumerScorecardRunner(renamedHost, fixture.executor)
          .preflight(
            fixture.request.copy(
              allowedDirtyPaths = setOf(Path.of("renamed.txt"), Path.of("original.txt"))
            )
          )
      }
    }

    "preflight requires all methods and approved debugger assertions" {
      withRunnerFixture { fixture ->
        fixture.request.runtimeValidation.writeText(
          validRuntimeValidation(REVISION).replace("\"runbookContracts\": true,", "")
        )

        shouldThrow<IllegalArgumentException> {
            ConsumerScorecardRunner(fixture.host, fixture.executor).preflight(fixture.request)
          }
          .message shouldContain "debugger assertions"
      }
    }

    "process commands remain argument lists and never pass through a shell" {
      withRunnerFixture { fixture ->
        ConsumerScorecardRunner(fixture.host, fixture.executor).preflight(fixture.request)

        fixture.host.commands.forEach { command ->
          command.none { it == "sh" || it == "bash" || it == "-c" } shouldBe true
        }
      }
    }

    "runner profiles every method and publishes one separately measured scorecard" {
      withRunnerFixture { fixture ->
        val executor = RecordingBenchmarkExecutor()

        val accepted = ConsumerScorecardRunner(fixture.host, executor).run(fixture.request)

        accepted shouldBe acceptedRun(fixture)
        Files.isDirectory(accepted) shouldBe true
        Files.exists(stagingRun(fixture)) shouldBe false
        Files.walk(accepted).use { paths ->
          paths
            .filter(Files::isRegularFile)
            .map { accepted.relativize(it).toString() }
            .sorted()
            .toList()
        } shouldContainExactly expectedAcceptedFiles()
        executor.commands shouldHaveSize 41
        executor.commands.filter { command -> command.any { "-agentpath:" in it } } shouldHaveSize
          24
        executor.commands.filter { it.first().endsWith("/bin/jfr") } shouldHaveSize 16

        val finalCommand = executor.commands.last()
        val runtimeRoot = Path.of(finalCommand[5]).parent
        Files.notExists(runtimeRoot) shouldBe true
        finalCommand shouldContainExactly
          listOf(
            "taskset",
            "--cpu-list",
            "0,2",
            fixture.request.javaExecutable.toAbsolutePath().normalize().toString(),
            "-jar",
            runtimeRoot.resolve("benchmark.jar").toString(),
            SCORECARD_SELECTOR,
            "-bm",
            "avgt",
            "-tu",
            "ms",
            "-t",
            "1",
            "-f",
            "5",
            "-wi",
            "10",
            "-i",
            "20",
            "-w",
            "1s",
            "-r",
            "1s",
            "-rf",
            "csv",
            "-rff",
            runtimeRoot.resolve("results.csv").toString(),
            "-jvmArgsAppend",
            expectedForkJvmArguments(runtimeRoot),
          )
        executor.commands.none { command ->
          command.any { it == "gradle" || it == "gradlew" || it.endsWith("/gradlew") }
        } shouldBe true

        val firstProfile = executor.commands.first()
        firstProfile[6] shouldBe
          "^com\\.salesforce\\.revoman\\.benchmark\\.ConsumerJourneyBenchmark\\.postmanV2TenStepRevUp$"
        firstProfile shouldContainExactly
          firstProfile.take(7) +
            listOf(
              "-bm",
              "avgt",
              "-tu",
              "ms",
              "-t",
              "1",
              "-f",
              "1",
              "-wi",
              "1",
              "-i",
              "1",
              "-w",
              "250ms",
              "-r",
              "250ms",
              "-jvmArgsAppend",
              firstProfile.last(),
            )
        firstProfile.last() shouldStartWith "${expectedForkJvmArguments(runtimeRoot)} -agentpath:"
        firstProfile.last() shouldContain "event=cpu"
        firstProfile.last() shouldContain "loglevel=warn"

        val manifest =
          Json.parseToJsonElement(Files.readString(accepted.resolve("manifest.json"))).jsonObject
        manifest.getValue("schemaVersion").jsonPrimitive.content shouldBe "1"
        manifest.getValue("studyId").jsonPrimitive.content shouldBe SCORECARD_STUDY_ID
        manifest.getValue("runId").jsonPrimitive.content shouldBe "20260902T010203Z"
        manifest.getValue("command").jsonArray.map { it.jsonPrimitive.content } shouldContainExactly
          finalCommand
        manifest.getValue("profilerFacts").jsonArray shouldHaveSize 24
        manifest.getValue("optimizationHypotheses").jsonArray shouldHaveSize 0
        manifest.getValue("raw").jsonObject.getValue("results").jsonPrimitive.content shouldBe
          "raw/results.csv"
        val identities = manifest.getValue("javaIdentities").jsonObject
        identities
          .getValue("gradleDaemon")
          .jsonObject
          .getValue("identity")
          .jsonPrimitive
          .content shouldBe
          "runtime version: 25.0.1; vendor: Test Gradle Vendor; VM: Test Gradle VM"
        identities.getValue("jmh").jsonObject.getValue("identity").jsonPrimitive.content shouldBe
          "selected executable: ${fixture.request.javaExecutable}; in-fork feature assertion: 25"
      }
    }

    "runner reports the calculated method and event profile count" {
      withRunnerFixture { fixture ->
        val output = ByteArrayOutputStream()
        val originalOut = System.out
        try {
          System.setOut(PrintStream(output, true, Charsets.UTF_8))

          ConsumerScorecardRunner(fixture.host, RecordingBenchmarkExecutor()).run(fixture.request)
        } finally {
          System.setOut(originalOut)
        }

        output.toString(Charsets.UTF_8) shouldContain
          "consumer-scorecard: profiling 24 method/event pairs"
      }
    }

    "runner creates each runtime profile parent before invoking the profiler child" {
      withRunnerFixture { fixture ->
        val delegate = RecordingBenchmarkExecutor()
        var observedProfiles = 0
        val executor = ProcessExecutor { command, workingDirectory ->
          if (command.any { "-agentpath:" in it }) {
            val recording =
              Path.of(command.last().substringAfter("file=").substringBefore(",loglevel=warn"))
            require(Files.isDirectory(recording.parent)) {
              "Runtime profile parent must exist before the profiler child starts"
            }
            observedProfiles += 1
          }
          delegate.execute(command, workingDirectory)
        }

        ConsumerScorecardRunner(fixture.host, executor).run(fixture.request)

        observedProfiles shouldBe 24
      }
    }

    "benchmark runtime inputs contain no project or private machine identity" {
      withRunnerFixture { fixture ->
        val externalJdk = Files.createTempDirectory("consumer-scorecard-external-jdk-")
        try {
          val launcherHome = externalJdk.resolve("launcher")
          val launcher = executable(launcherHome.resolve("bin/java"))
          launcherHome
            .resolve("lib/libasyncProfiler.so")
            .also { it.parent.createDirectories() }
            .writeText("x")
          executable(launcherHome.resolve("bin/jfr"))
          val inheritedHome = externalJdk.resolve("inherited")
          val inheritedJava = executable(inheritedHome.resolve("bin/java"))
          val request = fixture.request.copy(javaExecutable = launcher)
          val host =
            fixture.host.copy(
              launcher = launcher,
              inheritedJava = inheritedJava,
              javaHome = inheritedHome.toString(),
              privateMachineIdentity =
                PrivateMachineIdentity(
                  "private-user-marker",
                  "/home/private-user-marker",
                  "private-host-marker",
                ),
            )
          val delegate = RecordingBenchmarkExecutor()
          val workingDirectories = mutableListOf<Path>()
          var copiedJarBytes: ByteArray? = null
          val executor = ProcessExecutor { command, workingDirectory ->
            workingDirectories.add(workingDirectory)
            if (command.first() == "taskset") {
              copiedJarBytes = Files.readAllBytes(Path.of(command[5]))
            }
            delegate.execute(command, workingDirectory)
          }

          val accepted = ConsumerScorecardRunner(host, executor).run(request)

          val runtimeInput =
            (delegate.commands.flatten() + workingDirectories.map(Path::toString)).joinToString(
              "\n"
            )
          setOf(request.projectRoot.toString())
            .plus(host.privateMachineIdentity.values)
            .forEach(runtimeInput::shouldNotContain)
          delegate.commands
            .filter { "-jvmArgsAppend" in it }
            .map(List<String>::last)
            .forEach { forkArguments ->
              forkArguments shouldContain "-Duser.name=revoman-scorecard"
              forkArguments shouldContain "-Duser.home="
              forkArguments shouldContain "-Duser.dir="
            }
          checkNotNull(copiedJarBytes).toList() shouldContainExactly
            Files.readAllBytes(request.benchmarkJar).toList()
          val manifestContents = Files.readString(accepted.resolve("manifest.json"))
          manifestContents shouldNotContain request.projectRoot.toString()
          Json.parseToJsonElement(manifestContents)
            .jsonObject
            .getValue("dependencyFingerprint")
            .jsonPrimitive
            .content shouldBe dependencyFingerprint(request.projectRoot, request.benchmarkJar)
        } finally {
          externalJdk.deleteRecursively()
        }
      }
    }

    listOf(
        ExecutionFailure.PROFILE_EXIT to "profile child failure",
        ExecutionFailure.PROFILE_MISSING to "missing profile",
        ExecutionFailure.SUMMARY_EXIT to "summary child failure",
        ExecutionFailure.SUMMARY_EMPTY to "missing summary",
        ExecutionFailure.SUMMARY_SEMANTIC_ERROR to "semantic summary failure",
        ExecutionFailure.FINAL_EXIT to "final child failure",
        ExecutionFailure.MISSING_CSV to "missing CSV",
        ExecutionFailure.MALFORMED_CSV to "malformed CSV",
      )
      .forEach { (failure, case) ->
        "$case retains diagnostics and publishes no accepted run" {
          withRunnerFixture { fixture ->
            shouldThrow<Exception> {
              ConsumerScorecardRunner(fixture.host, RecordingBenchmarkExecutor(failure))
                .run(fixture.request)
            }

            Files.exists(acceptedRun(fixture)) shouldBe false
            Files.isRegularFile(stagingRun(fixture).resolve("failure-summary.txt")) shouldBe true
          }
        }
      }

    "scorecard rejection retains diagnostics and publishes no accepted run" {
      withRunnerFixture { fixture ->
        shouldThrow<IllegalArgumentException> {
          ConsumerScorecardRunner(
              fixture.host,
              RecordingBenchmarkExecutor(),
              reporter = { 2 },
            )
            .run(fixture.request)
        }

        Files.exists(acceptedRun(fixture)) shouldBe false
        Files.readString(stagingRun(fixture).resolve("failure-summary.txt")) shouldContain
          "Scorecard validation"
      }
    }

    "missing async profiler fails before any child process starts" {
      withRunnerFixture { fixture ->
        val profiler =
          fixture.request.javaExecutable.parent.parent.resolve("lib/libasyncProfiler.so")
        Files.delete(profiler)
        val executor = RecordingBenchmarkExecutor()

        shouldThrow<IllegalArgumentException> {
            ConsumerScorecardRunner(fixture.host, executor).run(fixture.request)
          }
          .message shouldContain "libasyncProfiler.so"

        executor.commands shouldBe emptyList()
        Files.exists(acceptedRun(fixture)) shouldBe false
        Files.isRegularFile(stagingRun(fixture).resolve("failure-summary.txt")) shouldBe true
      }
    }

    "fingerprint inputs fail preflight before any child process starts" {
      withRunnerFixture { fixture ->
        Files.delete(fixture.request.projectRoot.resolve("gradle/libs.versions.toml"))
        val executor = RecordingBenchmarkExecutor()

        shouldThrow<IllegalArgumentException> {
            ConsumerScorecardRunner(fixture.host, executor).run(fixture.request)
          }
          .message shouldContain "fingerprint"

        executor.commands shouldBe emptyList()
      }
    }

    "an accepted target collision remains untouched and retains the staged attempt" {
      withRunnerFixture { fixture ->
        val accepted = acceptedRun(fixture)
        Files.createDirectories(accepted)
        Files.writeString(accepted.resolve("owned.txt"), "keep")

        shouldThrow<IllegalArgumentException> {
          ConsumerScorecardRunner(fixture.host, RecordingBenchmarkExecutor()).run(fixture.request)
        }

        Files.readString(accepted.resolve("owned.txt")) shouldBe "keep"
        Files.isRegularFile(stagingRun(fixture).resolve("failure-summary.txt")) shouldBe true
      }
    }

    "final move failure leaves the complete staged attempt for diagnosis" {
      withRunnerFixture { fixture ->
        shouldThrow<IOException> {
          ConsumerScorecardRunner(
              fixture.host,
              RecordingBenchmarkExecutor(),
              publishMove = { _, _ -> throw IOException("injected move failure") },
            )
            .run(fixture.request)
        }

        Files.exists(acceptedRun(fixture)) shouldBe false
        Files.isRegularFile(stagingRun(fixture).resolve("scorecard.csv")) shouldBe true
        Files.readString(stagingRun(fixture).resolve("failure-summary.txt")) shouldContain
          "injected move failure"
      }
    }

    "move-then-throw is rolled back to a complete staged attempt" {
      withRunnerFixture { fixture ->
        shouldThrow<IOException> {
          ConsumerScorecardRunner(
              fixture.host,
              RecordingBenchmarkExecutor(),
              publishMove = { source, target ->
                Files.move(source, target)
                throw IOException("injected after move")
              },
            )
            .run(fixture.request)
        }

        Files.exists(acceptedRun(fixture)) shouldBe false
        Files.isRegularFile(stagingRun(fixture).resolve("scorecard.csv")) shouldBe true
        Files.readString(stagingRun(fixture).resolve("failure-summary.txt")) shouldContain
          "injected after move"
      }
    }

    "reporter success without every output is rejected before publication" {
      withRunnerFixture { fixture ->
        shouldThrow<IllegalArgumentException> {
          ConsumerScorecardRunner(
              fixture.host,
              RecordingBenchmarkExecutor(),
              reporter = { 0 },
            )
            .run(fixture.request)
        }

        Files.exists(acceptedRun(fixture)) shouldBe false
        Files.readString(stagingRun(fixture).resolve("failure-summary.txt")) shouldContain
          "reporter did not create"
      }
    }

    "dependency fingerprint is canonical and every input affects it" {
      val root = Files.createTempDirectory("scorecard-fingerprint-test-")
      try {
        val inputs =
          linkedMapOf(
            "z.jar" to root.resolve("z.jar").also { Files.writeString(it, "jar") },
            "a.toml" to root.resolve("a.toml").also { Files.writeString(it, "versions") },
            "m.properties" to
              root.resolve("m.properties").also { Files.writeString(it, "wrapper") },
          )
        val original = dependencyFingerprint(inputs)

        original.length shouldBe 64
        dependencyFingerprint(inputs.entries.reversed().associate { it.toPair() }) shouldBe original
        inputs.forEach { (_, path) ->
          val previous = Files.readString(path)
          Files.writeString(path, "$previous changed")
          dependencyFingerprint(inputs) shouldNotBe original
          Files.writeString(path, previous)
        }
      } finally {
        root.toFile().deleteRecursively()
      }
    }

    "compiled CLI parses every explicit option and repeatable dirty paths" {
      val root = Path.of("/project")
      var captured: ScorecardRunRequest? = null
      val exitCode =
        runConsumerScorecardMain(
          arrayOf(
            "--project-root",
            root.toString(),
            "--benchmark-jar",
            "/project/benchmarks/jmh.jar",
            "--java-executable",
            "/jdk/bin/java",
            "--java-feature",
            "25",
            "--gradle-daemon-java-feature",
            "25",
            "--gradle-daemon-runtime-version",
            "25.0.1",
            "--gradle-daemon-vendor",
            "Test Gradle Vendor",
            "--gradle-daemon-vm-name",
            "Test Gradle VM",
            "--gradle-max-workers",
            "1",
            "--library-version",
            "0.1.0",
            "--runtime-validation",
            "/project/runtime-validation.json",
            "--allowed-dirty-path",
            ".idea/misc.xml",
            "--allowed-dirty-path",
            ".idea/kotlinc.xml",
          )
        ) { request ->
          captured = request
          root.resolve("accepted")
        }

      exitCode shouldBe 0
      captured shouldBe
        ScorecardRunRequest(
          projectRoot = root,
          benchmarkJar = Path.of("/project/benchmarks/jmh.jar"),
          javaExecutable = Path.of("/jdk/bin/java"),
          javaFeature = 25,
          gradleDaemonJavaFeature = 25,
          gradleDaemonRuntimeVersion = "25.0.1",
          gradleDaemonVendor = "Test Gradle Vendor",
          gradleDaemonVmName = "Test Gradle VM",
          gradleMaxWorkers = 1,
          libraryVersion = "0.1.0",
          runtimeValidation = Path.of("/project/runtime-validation.json"),
          allowedDirtyPaths = setOf(Path.of(".idea/misc.xml"), Path.of(".idea/kotlinc.xml")),
        )
    }

    "compiled CLI maps parsing validation and execution failures to exit two" {
      runConsumerScorecardMain(arrayOf("--project-root", "/project")) {
        error("must not run")
      } shouldBe 2
      runConsumerScorecardMain(validCliArguments()) {
        throw IllegalArgumentException("rejected")
      } shouldBe 2
      runConsumerScorecardMain(validCliArguments() + arrayOf("--unknown", "value")) {
        error("must not run")
      } shouldBe 2
    }
  })

internal const val REVISION = "0123456789abcdef0123456789abcdef01234567"

internal data class RunnerFixture(
  val request: ScorecardRunRequest,
  val host: FakeScorecardHost,
  val executor: ProcessExecutor,
)

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
internal fun withRunnerFixture(assertion: (RunnerFixture) -> Unit) {
  val root = Files.createTempDirectory("consumer-scorecard-runner-test-")
  try {
    root.resolve("gradle/libs.versions.toml").also { it.parent.createDirectories() }.writeText("x")
    root
      .resolve("gradle/wrapper/gradle-wrapper.properties")
      .also { it.parent.createDirectories() }
      .writeText("distributionUrl=gradle-9.7.1-bin.zip")
    val launcherHome = root.resolve("jdk-launcher")
    val launcher = executable(launcherHome.resolve("bin/java"))
    launcherHome
      .resolve("lib/libasyncProfiler.so")
      .also { it.parent.createDirectories() }
      .writeText("x")
    executable(launcherHome.resolve("bin/jfr"))
    val inheritedHome = root.resolve("jdk-inherited")
    executable(inheritedHome.resolve("bin/java"))
    val jar = root.resolve("benchmarks/consumer-jmh.jar").also { it.parent.createDirectories() }
    writeJmhJar(jar)
    val validation = root.resolve("runtime-validation.json")
    validation.writeText(validRuntimeValidation(REVISION))
    val host =
      FakeScorecardHost(
        launcher = launcher,
        inheritedJava = inheritedHome.resolve("bin/java"),
        javaHome = inheritedHome.toString(),
      )
    assertion(
      RunnerFixture(
        ScorecardRunRequest(
          projectRoot = root,
          benchmarkJar = jar,
          javaExecutable = launcher,
          javaFeature = 25,
          gradleDaemonJavaFeature = 25,
          gradleDaemonRuntimeVersion = "25.0.1",
          gradleDaemonVendor = "Test Gradle Vendor",
          gradleDaemonVmName = "Test Gradle VM",
          gradleMaxWorkers = 1,
          libraryVersion = "0.1.0",
          runtimeValidation = validation,
          allowedDirtyPaths = emptySet(),
        ),
        host,
        ProcessExecutor { _, _ -> error("preflight must not start a benchmark") },
      )
    )
  } finally {
    root.deleteRecursively()
  }
}

private fun executable(path: Path): Path = path.also {
  it.parent.createDirectories()
  it.writeText("executable")
  check(it.toFile().setExecutable(true))
}

internal fun writeJmhJar(
  path: Path,
  mainClass: String? = "org.openjdk.jmh.Main",
  benchmarks: List<String> = SCORECARD_BENCHMARKS,
  includeVersionedClass: Boolean = false,
  multiRelease: Boolean = false,
) {
  ZipOutputStream(Files.newOutputStream(path)).use { zip ->
    if (mainClass != null) {
      zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
      val multiReleaseAttribute = if (multiRelease) "Multi-Release: true\r\n" else ""
      zip.write(
        "Manifest-Version: 1.0\r\nMain-Class: $mainClass\r\n$multiReleaseAttribute\r\n"
          .toByteArray()
      )
      zip.closeEntry()
    }
    zip.putNextEntry(ZipEntry("META-INF/BenchmarkList"))
    zip.write(benchmarks.joinToString("\n", transform = ::jmhBenchmarkListEntry).toByteArray())
    zip.closeEntry()
    zip.putNextEntry(ZipEntry("org/openjdk/jmh/Main.class"))
    zip.write(byteArrayOf(1))
    zip.closeEntry()
    if (includeVersionedClass) {
      zip.putNextEntry(ZipEntry("META-INF/versions/9/example/Versioned.class"))
      zip.write(byteArrayOf(1))
      zip.closeEntry()
    }
  }
}

private fun jmhBenchmarkListEntry(benchmark: String): String {
  val benchmarkClass = benchmark.substringBeforeLast('.')
  val benchmarkSimpleName = benchmarkClass.substringAfterLast('.')
  val method = benchmark.substringAfterLast('.')
  val generatedClass =
    "com.salesforce.revoman.benchmark.jmh_generated.${benchmarkSimpleName}_${method}_jmhTest"
  return "JMH S ${benchmarkClass.length} $benchmarkClass " +
    "S ${generatedClass.length} $generatedClass S ${method.length} $method " +
    "S 10 Throughput E A 1 1 1 E"
}

internal val SCORECARD_BENCHMARKS =
  listOf(
    "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.postmanV2TenStepRevUp",
    "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.postmanV2TenStepScriptedRevUp",
    "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.v3TenStepRevUp",
    "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.v3HundredStepRevUp",
    "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.v3TenStepScriptedRevUp",
    "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.threeKickEnvironmentHandoff",
    "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.threeStepRunbookWithContracts",
    "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.verboseHundredStepRundownJson",
  )

internal fun validRuntimeValidation(revision: String): String =
  """
  {
    "revision": "$revision",
    "timestamp": "2026-09-02T01:02:03Z",
    "methods": [
      "postmanV2TenStepRevUp",
      "postmanV2TenStepScriptedRevUp",
      "v3TenStepRevUp",
      "v3HundredStepRevUp",
      "v3TenStepScriptedRevUp",
      "threeKickEnvironmentHandoff",
      "threeStepRunbookWithContracts",
      "verboseHundredStepRundownJson"
    ],
    "debugger": {"tool": "JetBrains JVM debugger", "session": "consumer-scorecard"},
    "assertions": {
      "v2LoaderPath": true,
      "v3LoaderPaths": true,
      "scriptIsolation": true,
      "handlerInvocationCounts": true,
      "environmentHandoffs": true,
      "runbookContracts": true,
      "verbosePreparedRundown": true
    }
  }
  """
    .trimIndent()

internal data class FakeScorecardHost(
  val launcher: Path,
  val inheritedJava: Path,
  val javaHome: String,
  override val runnerJavaFeature: Int = 25,
  val launcherJavaFeature: Int = 25,
  val inheritedJavaFeature: Int = 25,
  val gitStatus: String = "",
  val unreadable: Set<Path> = emptySet(),
  val nonExecutable: Set<Path> = emptySet(),
  val systemFiles: Map<Path, String> = defaultSystemFiles(),
  val commands: MutableList<List<String>> = mutableListOf(),
  val privateMachineIdentity: PrivateMachineIdentity =
    PrivateMachineIdentity("private-user", "/home/private-user", "private-host"),
) : ScorecardHost {
  override val clock: Clock = Clock.fixed(Instant.parse("2026-09-02T01:02:03Z"), ZoneOffset.UTC)
  override val currentProcessId: Long = 999
  override val runnerJavaIdentity: String = "OpenJDK 25 runner"

  override fun resolvePrivateMachineIdentity(projectRoot: Path): PrivateMachineIdentity =
    privateMachineIdentity

  override fun environmentVariable(name: String): String? =
    when (name) {
      "JAVA_HOME" -> javaHome
      "PATH" -> ""
      else -> null
    }

  override fun readText(path: Path): String = systemFiles[path] ?: Files.readString(path)

  override fun list(directory: Path, glob: String): List<Path> =
    if (directory == Path.of("/sys/devices/system/cpu") && glob == "cpu[0-9]*") {
      systemFiles.keys
        .filter { it.fileName.toString() == "thread_siblings_list" }
        .map { it.parent.parent }
    } else {
      emptyList()
    }

  override fun isRegularFile(path: Path): Boolean = path in systemFiles || Files.isRegularFile(path)

  override fun isReadable(path: Path): Boolean =
    path !in unreadable && (path in systemFiles || Files.isReadable(path))

  override fun isExecutable(path: Path): Boolean =
    path !in nonExecutable && Files.isExecutable(path)

  override fun executeReadOnly(command: List<String>, workingDirectory: Path): ProcessResult {
    commands += command
    return when {
      command == listOf("git", "rev-parse", "HEAD") -> ProcessResult(0, "$REVISION\n", "")
      command == listOf("git", "status", "--porcelain=v1", "-z") -> ProcessResult(0, gitStatus, "")
      command == listOf("uname", "-srvm") -> ProcessResult(0, "Linux 6.17.0 x86_64 GNU/Linux\n", "")
      command == listOf("ps", "-eo", "pid=,comm=") ||
        command == listOf("ps", "-eo", "pid=,ppid=,comm=") ->
        ProcessResult(0, "11 1 codex\n12 1 gnome-shell\n13 1 nxserver\n", "")
      command == listOf("jps", "-l") -> ProcessResult(0, "", "")
      command.first() == launcher.toString() -> javaResult(launcherJavaFeature, "launcher")
      command.first() == inheritedJava.toString() -> javaResult(inheritedJavaFeature, "inherited")
      else -> error("Unexpected read-only command: $command")
    }
  }
}

private fun defaultSystemFiles(): Map<Path, String> =
  mapOf(
    Path.of("/proc/self/status") to "Name:\tjava\nCpus_allowed_list:\t0-3\n",
    Path.of("/sys/devices/system/cpu/online") to "0-3\n",
    Path.of("/sys/devices/system/cpu/cpu0/topology/thread_siblings_list") to "0-1\n",
    Path.of("/sys/devices/system/cpu/cpu1/topology/thread_siblings_list") to "0-1\n",
    Path.of("/sys/devices/system/cpu/cpu2/topology/thread_siblings_list") to "2-3\n",
    Path.of("/sys/devices/system/cpu/cpu3/topology/thread_siblings_list") to "2-3\n",
    Path.of("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor") to "performance\n",
    Path.of("/sys/devices/system/cpu/cpu2/cpufreq/scaling_governor") to "performance\n",
    Path.of("/proc/cpuinfo") to "processor: 0\nmodel name: Test CPU 9000\n",
    Path.of("/proc/meminfo") to "MemTotal: 32768000 kB\n",
    Path.of("/proc/loadavg") to "0.10 0.20 0.30 2/100 1234\n",
  )

internal enum class ExecutionFailure {
  PROFILE_EXIT,
  PROFILE_MISSING,
  SUMMARY_EXIT,
  SUMMARY_EMPTY,
  SUMMARY_SEMANTIC_ERROR,
  FINAL_EXIT,
  MISSING_CSV,
  MALFORMED_CSV,
}

internal class RecordingBenchmarkExecutor(private val failure: ExecutionFailure? = null) :
  ProcessExecutor {
  val commands = mutableListOf<List<String>>()

  override fun execute(command: List<String>, workingDirectory: Path): ProcessResult {
    commands += command
    return when {
      command.first().endsWith("/bin/jfr") -> summaryResult()
      command.any { "-agentpath:" in it } -> profileResult(command)
      command.contains("-rff") -> {
        if (failure == ExecutionFailure.FINAL_EXIT) {
          ProcessResult(1, "", "final failed")
        } else {
          if (failure != ExecutionFailure.MISSING_CSV) {
            val results = Path.of(command[command.indexOf("-rff") + 1])
            Files.createDirectories(results.parent)
            Files.writeString(
              results,
              if (failure == ExecutionFailure.MALFORMED_CSV) {
                "malformed"
              } else {
                consumerScorecardCsv()
              },
            )
          }
          ProcessResult(0, "final complete\n", "")
        }
      }
      else -> error("Unexpected benchmark command: $command")
    }
  }

  private fun summaryResult(): ProcessResult =
    when (failure) {
      ExecutionFailure.SUMMARY_EXIT -> ProcessResult(1, "", "summary failed")
      ExecutionFailure.SUMMARY_EMPTY -> ProcessResult(0, "", "")
      ExecutionFailure.SUMMARY_SEMANTIC_ERROR ->
        ProcessResult(
          0,
          "Can't find event type named 'ObjectAllocationSample'.\n" +
            "Missing event found for allocation-by-class\n",
          "",
        )
      else -> ProcessResult(0, "fixed JFR view\n", "")
    }

  private fun profileResult(command: List<String>): ProcessResult {
    if (failure == ExecutionFailure.PROFILE_EXIT) {
      return ProcessResult(1, "", "profile failed")
    }
    if (failure != ExecutionFailure.PROFILE_MISSING) {
      val path = Path.of(command.last().substringAfter("file=").substringBefore(",loglevel=warn"))
      Files.createDirectories(path.parent)
      Files.write(
        path,
        if (command.last().contains("event=alloc")) {
          testAllocationRecordingBytes
        } else {
          byteArrayOf(1, 2, 3)
        },
      )
    }
    return ProcessResult(0, "profile complete\n", "")
  }
}

private fun consumerScorecardCsv(): String =
  checkNotNull(ConsumerScorecardRunnerTest::class.java.getResource("/jmh/consumer-scorecard.csv"))
    .readText()

private fun expectedForkJvmArguments(runtimeRoot: Path): String =
  listOf(
      "-Drevoman.scorecard.expectedJavaFeature=25",
      "-Drevoman.banner=off",
      "-Duser.name=revoman-scorecard",
      "-Duser.home=${runtimeRoot.resolve("home")}",
      "-Duser.dir=$runtimeRoot",
      "-Djava.io.tmpdir=${runtimeRoot.resolve("tmp")}",
    )
    .joinToString(" ")

internal fun stagingRun(fixture: RunnerFixture): Path =
  fixture.request.projectRoot
    .resolve(".benchmark-staging/consumer-performance-scorecard/20260902T010203Z")
    .toAbsolutePath()
    .normalize()

internal fun acceptedRun(fixture: RunnerFixture): Path =
  fixture.request.projectRoot
    .resolve("benchmark-results/consumer-performance-scorecard/20260902T010203Z")
    .toAbsolutePath()
    .normalize()

private fun expectedAcceptedFiles(): List<String> {
  val profiles =
    listOf(
        "postmanV2TenStepRevUp",
        "postmanV2TenStepScriptedRevUp",
        "v3TenStepRevUp",
        "v3HundredStepRevUp",
        "v3TenStepScriptedRevUp",
        "threeKickEnvironmentHandoff",
        "threeStepRunbookWithContracts",
        "verboseHundredStepRundownJson",
      )
      .flatMap { method ->
        listOf("cpu", "alloc", "lock").flatMap { event ->
          listOf(
            "raw/profiles/$method/$event.jfr",
            "raw/profiles/$method/$event.txt",
          )
        }
      }
  return (listOf(
      "environment/run.json",
      "manifest.json",
      "performance-scorecard.adoc",
      "raw/results.csv",
      "report.md",
      "scorecard.csv",
    ) + profiles)
    .sorted()
}

private fun validCliArguments(): Array<String> =
  arrayOf(
    "--project-root",
    "/project",
    "--benchmark-jar",
    "/project/benchmarks/jmh.jar",
    "--java-executable",
    "/jdk/bin/java",
    "--java-feature",
    "25",
    "--gradle-daemon-java-feature",
    "25",
    "--gradle-daemon-runtime-version",
    "25.0.1",
    "--gradle-daemon-vendor",
    "Test Gradle Vendor",
    "--gradle-daemon-vm-name",
    "Test Gradle VM",
    "--gradle-max-workers",
    "1",
    "--library-version",
    "0.1.0",
    "--runtime-validation",
    "/project/runtime-validation.json",
  )

private fun javaResult(feature: Int, label: String): ProcessResult =
  ProcessResult(
    0,
    "",
    """
    Property settings:
        java.runtime.name = OpenJDK Runtime Environment
        java.version = $feature.0.1
        java.vm.name = OpenJDK 64-Bit Server VM
        java.vendor = Test Vendor
    openjdk version "$feature.0.1"
    $label
    """
      .trimIndent(),
  )
