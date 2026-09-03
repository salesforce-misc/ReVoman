import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class ConsumerScorecardPluginFunctionalTest :
    FunSpec({
        test("registers a lazy Java 25 scorecard task backed by the executable artifact") {
            ScorecardFixture.create().use { fixture ->
                fixture.build(":benchmark-reporting:tasks", "--all").output shouldContain
                        "runConsumerScorecard"

                val result =
                    fixture.build(
                        ":benchmark-reporting:inspectConsumerScorecard",
                        "--max-workers=3",
                        "-PscorecardRuntimeValidation=${fixture.runtimeValidation}",
                        "-PscorecardAllowedDirty=.idea/kotlinc.xml, .idea/misc.xml",
                    )

                result.output shouldContain "SCORECARD_TYPE=JavaExec"
                result.output shouldContain "SCORECARD_LAUNCHER=25"
                result.output shouldContain "SCORECARD_DEPENDENCY=:benchmarks:mainBenchmarkJar"
                result.output shouldContain "SCORECARD_MAX_WORKERS=3"
                result.output shouldContain "SCORECARD_ARG=--library-version"
                result.output shouldContain "SCORECARD_ARG=9.9.9"
                result.output shouldContain "SCORECARD_ARG=--allowed-dirty-path"
                result.output shouldContain "SCORECARD_ARG=.idea/kotlinc.xml"
                result.output shouldContain "SCORECARD_ARG=.idea/misc.xml"
                result.output shouldContain "SCORECARD_INPUT=scorecard.projectRoot"
                result.output shouldContain "SCORECARD_INPUT=scorecard.benchmarkJar"
                result.output shouldContain "SCORECARD_INPUT=scorecard.javaExecutable"
                result.output shouldContain "SCORECARD_INPUT=scorecard.javaFeature"
                result.output shouldContain "SCORECARD_INPUT=scorecard.gradleDaemonJavaFeature"
                result.output shouldContain "SCORECARD_INPUT=scorecard.gradleDaemonRuntimeVersion"
                result.output shouldContain "SCORECARD_INPUT=scorecard.gradleDaemonVendor"
                result.output shouldContain "SCORECARD_INPUT=scorecard.gradleDaemonVmName"
                result.output shouldContain "SCORECARD_INPUT=gradleMaxWorkers"
                result.output shouldContain "SCORECARD_INPUT=scorecard.libraryVersion"
                result.output shouldContain "SCORECARD_INPUT=scorecard.runtimeValidation"
                result.output shouldContain "SCORECARD_INPUT=scorecard.allowedDirtyPaths"
                result.task(":benchmarks:mainBenchmarkJar")?.outcome shouldBe TaskOutcome.SUCCESS
                fixture.childMarker.toFile().exists() shouldBe false
            }
        }

        test("real convention plugins expose the fixed profile and executable artifact bridge") {
            ScorecardFixture.create().use { fixture ->
                val result =
                    fixture.build(
                        ":benchmarks:inspectBenchmarkConventions",
                        ":benchmark-reporting:inspectConsumerScorecardBridge",
                    )

                result.output shouldContain
                        "PROFILE_consumerScorecard=avgt|ms|csv|20|10|1000|ms|5|" +
                        "com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.*|{}"
                result.output shouldContain "PROFILE_smoke=avgt|ms|csv|2|2|250|ms|1||{}"
                result.output shouldContain "PROFILE_final=avgt|ms|csv|20|10|1000|ms|5||{}"
                result.output shouldContain
                        "PROFILE_collectionScaleFinal=avgt|ms|csv|20|10|1000|ms|5|" +
                        "com.salesforce.revoman.benchmark.CollectionScaleRevUpBenchmark.revUpByStepCount|" +
                        "{stepCount=[100, 500]}"
                result.output shouldContain "PRODUCER_CAN_BE_CONSUMED=true"
                result.output shouldContain "PRODUCER_CAN_BE_RESOLVED=false"
                result.output shouldContain "PRODUCER_ARTIFACTS=1"
                result.output shouldContain "PRODUCER_TASK=:benchmarks:mainBenchmarkJar"
                result.output shouldContain "REPORTING_CAN_BE_CONSUMED=false"
                result.output shouldContain "REPORTING_CAN_BE_RESOLVED=true"
                result.output shouldContain "REPORTING_FILES=1"
                result.output shouldContain "REPORTING_DEPENDENCY=:benchmarks"
                result.output shouldContain "COMPILE_DEPENDENCY=:benchmarks=false"
                listOf(
                    "org.gradle.category=library",
                    "org.gradle.usage=java-runtime",
                    "org.gradle.libraryelements=jar",
                    "org.gradle.dependency.bundling=shadowed",
                ).forEach { attribute ->
                    result.output shouldContain "PRODUCER_ATTRIBUTE=$attribute"
                    result.output shouldContain "REPORTING_ATTRIBUTE=$attribute"
                }

                val benchmarkJar = Path.of(fixture.bridgeJar.toFile().readText().trim())
                benchmarkJar.shouldExist()
                ZipFile(benchmarkJar.toFile()).use { archive ->
                    (archive.getEntry("org/openjdk/jmh/Main.class") != null) shouldBe true
                    (archive.getEntry("fixture/FixtureBenchmark.class") != null) shouldBe true
                }
            }
        }

        test("requires runtime validation only when the scorecard task executes") {
            ScorecardFixture.create().use { fixture ->
                shouldNotThrowAny { fixture.build(":benchmark-reporting:tasks", "--all") }

                val result = fixture.buildAndFail(":benchmark-reporting:runConsumerScorecard")

                result.output shouldContain "scorecardRuntimeValidation"
                fixture.childMarker.toFile().exists() shouldBe false
            }
        }

        test("stores the scorecard task in the configuration cache") {
            ScorecardFixture.create().use { fixture ->
                val result =
                    fixture.build(
                        ":benchmark-reporting:runConsumerScorecard",
                        "--dry-run",
                        "--configuration-cache",
                    )

                result.output shouldContain "Configuration cache entry stored"
            }
        }

        test("configuration cache reuse observes max workers changing from three to one") {
            ScorecardFixture.create().use { fixture ->
                fixture.build(":benchmarks:mainBenchmarkJar")
                val rejected =
                    fixture.buildAndFail(
                        ":benchmark-reporting:runConsumerScorecard",
                        "--configuration-cache",
                        "--max-workers=3",
                        "-PscorecardRuntimeValidation=${fixture.runtimeValidation}",
                    )
                rejected.output shouldContain "Scorecard requires exactly one worker; found 3"
                rejected.output shouldContain "Configuration cache entry stored"

                val accepted =
                    fixture.build(
                        ":benchmark-reporting:runConsumerScorecard",
                        "--configuration-cache",
                        "--max-workers=1",
                        "-PscorecardRuntimeValidation=${fixture.runtimeValidation}",
                    )
                accepted.output shouldContain "Reusing configuration cache"
                fixture.childMarker.toFile().readText() shouldContain "--gradle-max-workers=1"
            }
        }

        test("configuration cache reuse observes max workers changing from one to three") {
            ScorecardFixture.create().use { fixture ->
                fixture.build(":benchmarks:mainBenchmarkJar")
                fixture.build(
                    ":benchmark-reporting:runConsumerScorecard",
                    "--configuration-cache",
                    "--max-workers=1",
                    "-PscorecardRuntimeValidation=${fixture.runtimeValidation}",
                )
                Files.delete(fixture.childMarker)

                val rejected =
                    fixture.buildAndFail(
                        ":benchmark-reporting:runConsumerScorecard",
                        "--configuration-cache",
                        "--max-workers=3",
                        "-PscorecardRuntimeValidation=${fixture.runtimeValidation}",
                    )
                rejected.output shouldContain "Reusing configuration cache"
                rejected.output shouldContain "Scorecard requires exactly one worker; found 3"
                fixture.childMarker.toFile().exists() shouldBe false
            }
        }

        test("rejects a Java 21 scorecard launcher before starting the child")
            .config(enabledIf = { optionalJava21Home() != null }) {
                ScorecardFixture.create().use { fixture ->
                    val java21Home = requireNotNull(optionalJava21Home())
                    java21Home.resolve("bin/java").shouldExist()

                    val result =
                        fixture.buildAndFail(
                            ":benchmark-reporting:runConsumerScorecard",
                            "--max-workers=1",
                            "-PscorecardRuntimeValidation=${fixture.runtimeValidation}",
                            "-PscorecardTestJavaLauncher=21",
                            "-Porg.gradle.java.installations.auto-detect=false",
                            "-Porg.gradle.java.installations.auto-download=false",
                            "-Porg.gradle.java.installations.paths=$java21Home",
                        )

                    result.output shouldContain
                            "Scorecard Java launcher feature 25 is required; found 21"
                    fixture.childMarker.toFile().exists() shouldBe false
                }
            }

        test("runs the fake scorecard child with the declared Java 25 inputs") {
            Runtime.version().feature() shouldBe 25
            ScorecardFixture.create().use { fixture ->
                val result =
                    fixture.build(
                        ":benchmark-reporting:runConsumerScorecard",
                        "--max-workers=1",
                        "-PscorecardRuntimeValidation=${fixture.runtimeValidation}",
                        "-PscorecardAllowedDirty=.idea/kotlinc.xml,.idea/misc.xml",
                    )

                result.task(":benchmarks:mainBenchmarkJar")?.outcome shouldBe TaskOutcome.SUCCESS
                fixture.childMarker.shouldExist()
                fixture.fakeCsv.shouldExist()
                fixture.fakeCsv.toFile().readText() shouldContain "Benchmark,Mode,Threads,Samples,Score"
                val childOutput = fixture.childMarker.toFile().readLines()
                childOutput shouldContainAll
                        listOf(
                            "VALIDATION_REACHED",
                            "--java-feature=25",
                            "--gradle-daemon-java-feature=25",
                            "--gradle-daemon-runtime-version=${System.getProperty("java.runtime.version")}",
                            "--gradle-daemon-vendor=${System.getProperty("java.vendor")}",
                            "--gradle-daemon-vm-name=${System.getProperty("java.vm.name")}",
                            "--gradle-max-workers=1",
                            "--library-version=9.9.9",
                            "--allowed-dirty-path=.idea/kotlinc.xml",
                            "--allowed-dirty-path=.idea/misc.xml",
                        )
                childOutput.joinToString("\n") shouldNotContain "gradle "
            }
        }
    })

private fun optionalJava21Home(): Path? =
    System.getProperty("consumerScorecardTest.java21Home")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.takeIf { javaHome ->
            javaHome.resolve("release").toFile().readLines().any { line ->
                line.startsWith("JAVA_VERSION=\"21")
            }
        }

private class ScorecardFixture private constructor(val root: Path) : AutoCloseable {
    val runtimeValidation: Path = root.resolve("runtime-validation.json")
    val childMarker: Path = root.resolve("build/fake-child.marker")
    val fakeCsv: Path = root.resolve("build/fake-results.csv")
    val bridgeJar: Path = root.resolve("build/bridge-jar.path")

    fun build(vararg arguments: String): BuildResult = runner(arguments.toList()).build()

    fun buildAndFail(vararg arguments: String): BuildResult = runner(arguments.toList()).buildAndFail()

    private fun runner(arguments: List<String>): GradleRunner {
        val defaultWorkerArgument =
            when {
                arguments.any { value -> value.startsWith("--max-workers") } -> emptyList()
                else -> listOf("--max-workers=1")
            }
        return GradleRunner.create()
            .withProjectDir(root.toFile())
            .withTestKitDir(
                Path.of(System.getProperty("user.dir")).resolve("build/test-kit").toFile()
            )
            .withPluginClasspath()
            .withArguments(listOf("--stacktrace") + defaultWorkerArgument + arguments)
    }

    override fun close() {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object {
        fun create(): ScorecardFixture {
            val root = Files.createTempDirectory("consumer-scorecard-plugin-")
            val fixture = ScorecardFixture(root)
            fixture.writeBuild()
            return fixture
        }
    }

    private fun writeBuild() {
        write(
            "settings.gradle.kts",
            """
      pluginManagement {
        repositories {
          mavenCentral()
          gradlePluginPortal()
          google()
        }
      }
      dependencyResolutionManagement {
        repositories {
          mavenCentral()
          maven("https://oss.sonatype.org/content/repositories/snapshots")
        }
      }
      rootProject.name = "consumer-scorecard-functional-test"
      include(":benchmarks", ":benchmark-reporting")
      """,
        )
        val versionCatalog =
            Path.of(System.getProperty("user.dir"))
                .resolve("../gradle/libs.versions.toml")
                .normalize()
        Files.createDirectories(root.resolve("gradle"))
        Files.copy(versionCatalog, root.resolve("gradle/libs.versions.toml"))
        write("gradle.properties", "revoman.version=9.9.9\n")
        write("build.gradle.kts", "")
        write("runtime-validation.json", "{}\n")
        write(
            "benchmarks/build.gradle.kts",
            """
      import kotlinx.benchmark.gradle.BenchmarksExtension

      plugins { id("revoman.benchmarks") }

      dependencies {
        implementation(libs.kotlinx.benchmark.runtime)
      }

      val benchmarkProfiles = extensions.getByType<BenchmarksExtension>().configurations
      val consumerScorecardExecutable = configurations.named("consumerScorecardExecutable")

      tasks.register("inspectBenchmarkConventions") {
        doLast {
          listOf("consumerScorecard", "smoke", "final", "collectionScaleFinal").forEach { name ->
            val profile = benchmarkProfiles.getByName(name)
            println(
              "PROFILE_${'$'}name=" +
                listOf(
                  profile.mode,
                  profile.outputTimeUnit,
                  profile.reportFormat,
                  profile.iterations,
                  profile.warmups,
                  profile.iterationTime,
                  profile.iterationTimeUnit,
                  profile.advanced["jvmForks"],
                  profile.includes.joinToString(),
                  profile.params,
                ).joinToString("|")
            )
          }
          val producer = consumerScorecardExecutable.get()
          println("PRODUCER_CAN_BE_CONSUMED=${'$'}{producer.isCanBeConsumed}")
          println("PRODUCER_CAN_BE_RESOLVED=${'$'}{producer.isCanBeResolved}")
          println("PRODUCER_ARTIFACTS=${'$'}{producer.outgoing.artifacts.size}")
          producer.attributes.keySet().sortedBy { it.name }.forEach { attribute ->
            println("PRODUCER_ATTRIBUTE=${'$'}{attribute.name}=${'$'}{producer.attributes.getAttribute(attribute)}")
          }
          producer.outgoing.artifacts.flatMap { artifact ->
            artifact.buildDependencies.getDependencies(this)
          }.sortedBy { it.path }.forEach { println("PRODUCER_TASK=${'$'}{it.path}") }
        }
      }
      """,
        )
        write(
            "benchmarks/src/main/kotlin/FixtureBenchmark.kt",
            """
      package fixture

      import kotlinx.benchmark.Benchmark
      import kotlinx.benchmark.Scope
      import kotlinx.benchmark.State

      @State(Scope.Benchmark)
      open class FixtureBenchmark {
        @Benchmark
        fun benchmark(): Int = 42
      }
      """,
        )
        write(
            "benchmark-reporting/build.gradle.kts",
            """
      plugins { id("revoman.benchmark-reporting") }

      val consumerScorecardExecutable = configurations.named("consumerScorecardExecutable")
      val javaToolchains = extensions.getByType<JavaToolchainService>()

      providers.gradleProperty("scorecardTestJavaLauncher").orNull?.let { feature ->
        tasks.named<JavaExec>("runConsumerScorecard") {
          javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(feature.toInt())
          }
        }
      }

      tasks.register("inspectConsumerScorecard") {
        dependsOn(consumerScorecardExecutable)
        doLast {
          val scorecard = tasks.named<JavaExec>("runConsumerScorecard").get()
          println("SCORECARD_TYPE=JavaExec")
          println("SCORECARD_LAUNCHER=${'$'}{scorecard.javaLauncher.get().metadata.languageVersion}")
          println("SCORECARD_MAX_WORKERS=${'$'}{scorecard.inputs.properties.getValue("gradleMaxWorkers")}")
          scorecard.taskDependencies.getDependencies(scorecard).sortedBy { it.path }.forEach {
            println("SCORECARD_DEPENDENCY=${'$'}{it.path}")
          }
          scorecard.inputs.properties.keys.sorted().forEach { println("SCORECARD_INPUT=${'$'}it") }
          scorecard.argumentProviders.flatMap { it.asArguments() }.forEach {
            println("SCORECARD_ARG=${'$'}it")
          }
        }
      }

      tasks.register("inspectConsumerScorecardBridge") {
        dependsOn(consumerScorecardExecutable)
        doLast {
          val reporting = consumerScorecardExecutable.get()
          println("REPORTING_CAN_BE_CONSUMED=${'$'}{reporting.isCanBeConsumed}")
          println("REPORTING_CAN_BE_RESOLVED=${'$'}{reporting.isCanBeResolved}")
          println("REPORTING_FILES=${'$'}{reporting.files.size}")
          reporting.dependencies.forEach { dependency ->
            println(
              "REPORTING_DEPENDENCY=" +
                (dependency as org.gradle.api.artifacts.ProjectDependency).path
            )
          }
          val compileDependencies = configurations.getByName("compileClasspath").dependencies
          println(
            "COMPILE_DEPENDENCY=:benchmarks=" +
              compileDependencies.any { it.name == "benchmarks" }
          )
          reporting.attributes.keySet().sortedBy { it.name }.forEach { attribute ->
            println("REPORTING_ATTRIBUTE=${'$'}{attribute.name}=${'$'}{reporting.attributes.getAttribute(attribute)}")
          }
          val bridgePath = rootProject.layout.buildDirectory.file("bridge-jar.path").get().asFile
          bridgePath.parentFile.mkdirs()
          bridgePath.writeText(reporting.singleFile.absolutePath)
        }
      }
      """,
        )
        write(
            "benchmark-reporting/src/main/java/com/salesforce/revoman/benchmark/reporting/ConsumerScorecardMainKt.java",
            """
      package com.salesforce.revoman.benchmark.reporting;

      import java.nio.file.Files;
      import java.nio.file.Path;
      import java.util.LinkedHashMap;
      import java.util.Map;

      public final class ConsumerScorecardMainKt {
        private ConsumerScorecardMainKt() {}

        public static void main(String[] args) throws Exception {
          Map<String, String> values = new LinkedHashMap<>();
          for (int index = 0; index < args.length; index += 2) {
            values.put(args[index], args[index + 1]);
          }
          Path projectRoot = Path.of(values.get("--project-root"));
          if (!Files.isRegularFile(Path.of(values.get("--benchmark-jar")))) {
            throw new IllegalStateException("benchmark artifact was not built");
          }
          if (!Files.isRegularFile(Path.of(values.get("--runtime-validation")))) {
            throw new IllegalStateException("runtime validation was not supplied");
          }
          int maxWorkers = Integer.parseInt(values.get("--gradle-max-workers"));
          if (maxWorkers != 1) {
            throw new IllegalStateException(
                "Scorecard requires exactly one worker; found " + maxWorkers);
          }
          Path buildDirectory = projectRoot.resolve("build");
          Files.createDirectories(buildDirectory);
          Files.writeString(
              buildDirectory.resolve("fake-results.csv"),
              "Benchmark,Mode,Threads,Samples,Score,Score Error (99.9%),Unit\n");
          StringBuilder marker = new StringBuilder("VALIDATION_REACHED\n");
          for (int index = 0; index < args.length; index += 2) {
            marker.append(args[index]).append('=').append(args[index + 1]).append('\n');
          }
          Files.writeString(buildDirectory.resolve("fake-child.marker"), marker.toString());
        }
      }
      """,
        )
    }

    private fun write(relativePath: String, content: String) {
        val target = root.resolve(relativePath)
        Files.createDirectories(target.parent)
        Files.writeString(target, content.trimIndent())
    }
}
