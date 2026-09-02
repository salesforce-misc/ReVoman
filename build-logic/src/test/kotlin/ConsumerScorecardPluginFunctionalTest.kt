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
                result.output shouldContain "SCORECARD_DEPENDENCY=:benchmarks:fakeConsumerScorecardJar"
                result.output shouldContain "SCORECARD_ARG=--gradle-max-workers"
                result.output shouldContain "SCORECARD_ARG=3"
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
                result.output shouldContain "SCORECARD_INPUT=scorecard.gradleMaxWorkers"
                result.output shouldContain "SCORECARD_INPUT=scorecard.libraryVersion"
                result.output shouldContain "SCORECARD_INPUT=scorecard.runtimeValidation"
                result.output shouldContain "SCORECARD_INPUT=scorecard.allowedDirtyPaths"
                result.task(":benchmarks:fakeConsumerScorecardJar")?.outcome shouldBe TaskOutcome.SUCCESS
                fixture.childMarker.toFile().exists() shouldBe false
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

                result.task(":benchmarks:fakeConsumerScorecardJar")?.outcome shouldBe TaskOutcome.SUCCESS
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

    fun build(vararg arguments: String): BuildResult = runner(arguments.toList()).build()

    fun buildAndFail(vararg arguments: String): BuildResult = runner(arguments.toList()).buildAndFail()

    private fun runner(arguments: List<String>): GradleRunner =
        GradleRunner.create()
            .withProjectDir(root.toFile())
            .withTestKitDir(root.resolve(".test-kit").toFile())
            .withPluginClasspath()
            .withArguments(listOf("--stacktrace") + arguments)

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
        write("benchmarks/payload.txt", "fake benchmark payload\n")
        write(
            "benchmarks/build.gradle.kts",
            """
      plugins { base }

      val consumerScorecardExecutable = configurations.create("consumerScorecardExecutable") {
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes {
          attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
          attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
          attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
          attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
      }
      val fakeConsumerScorecardJar = tasks.register<Jar>("fakeConsumerScorecardJar") {
        archiveFileName = "fake-consumer-scorecard.jar"
        destinationDirectory = layout.buildDirectory.dir("scorecard")
        from("payload.txt")
      }
      artifacts.add(consumerScorecardExecutable.name, fakeConsumerScorecardJar)
      """,
        )
        write(
            "benchmark-reporting/build.gradle.kts",
            """
      plugins { id("revoman.benchmark-reporting") }

      val consumerScorecardExecutable = configurations.named("consumerScorecardExecutable")
      val javaToolchains = extensions.getByType<JavaToolchainService>()
      dependencies {
        add(
          consumerScorecardExecutable.name,
          project(path = ":benchmarks", configuration = "consumerScorecardExecutable"),
        )
      }

      providers.gradleProperty("scorecardTestJavaLauncher").orNull?.let { feature ->
        tasks.named<JavaExec>("runConsumerScorecard") {
          javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(feature.toInt())
          }
        }
      }

      tasks.register("inspectConsumerScorecard") {
        dependsOn(":benchmarks:fakeConsumerScorecardJar")
        doLast {
          val scorecard = tasks.named<JavaExec>("runConsumerScorecard").get()
          println("SCORECARD_TYPE=JavaExec")
          println("SCORECARD_LAUNCHER=${'$'}{scorecard.javaLauncher.get().metadata.languageVersion}")
          scorecard.taskDependencies.getDependencies(scorecard).sortedBy { it.path }.forEach {
            println("SCORECARD_DEPENDENCY=${'$'}{it.path}")
          }
          scorecard.inputs.properties.keys.sorted().forEach { println("SCORECARD_INPUT=${'$'}it") }
          scorecard.argumentProviders.flatMap { it.asArguments() }.forEach {
            println("SCORECARD_ARG=${'$'}it")
          }
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
