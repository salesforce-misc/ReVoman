import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

abstract class StrictJmhJavaExec : JavaExec() {
  @get:InputDirectory abstract val installationRoot: DirectoryProperty

  @get:InputFile abstract val targetManifest: RegularFileProperty

  @get:InputFile abstract val logConfig: RegularFileProperty

  @get:Optional @get:Input abstract val logConfigOverride: Property<String>

  @get:Input abstract val adapterId: Property<String>

  @get:Input abstract val includes: ListProperty<String>

  @get:Input abstract val quick: Property<Boolean>

  @get:Optional @get:Input abstract val forks: Property<String>

  @get:Optional @get:Input abstract val profilers: Property<String>

  @get:OutputFile abstract val rawOutput: RegularFileProperty

  @get:OutputFile abstract val humanOutput: RegularFileProperty

  @get:OutputFile abstract val normalizedOutput: RegularFileProperty

  @TaskAction
  override fun exec() {
    val root = installationRoot.get().asFile.toPath().toRealPath()
    val lib = root.resolve("lib")
    val thinJar = lib.resolve("benchmark-driver-jmh-classes.jar")
    check(Files.isRegularFile(thinJar)) { "Missing fixed thin JMH classes JAR: $thinJar" }
    val installedJars =
      Files.list(lib).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".jar") }.sorted().toList()
      }
    check(installedJars.none { it.fileName.toString().endsWith("-jmh.jar") }) {
      "Installed benchmark classpath contains a forbidden JMH uber-JAR: $installedJars"
    }

    logConfigOverride.orNull?.let { supplied ->
      require(Path.of(supplied).isAbsolute) {
        "benchmark.logConfig must be an absolute file: $supplied"
      }
    }
    val logging = logConfig.get().asFile.toPath().toRealPath()
    val manifest = targetManifest.get().asFile.toPath().toRealPath()
    val fixtureRoot = root.resolve("workloads/v1/jmh.component-operations.v1").toRealPath()
    val raw = rawOutput.get().asFile.toPath().toAbsolutePath().normalize()
    val normalized = normalizedOutput.get().asFile.toPath().toAbsolutePath().normalize()
    val human = humanOutput.get().asFile.toPath().toAbsolutePath().normalize()
    val rawTemporary = raw.resolveSibling(".${raw.fileName}.tmp")
    val token = rawTemporary.resolveSibling("${rawTemporary.fileName}.target-token.json")
    listOf(raw, rawTemporary, normalized, human).forEach { output ->
      Files.createDirectories(requireNotNull(output.parent))
      Files.deleteIfExists(output)
    }
    if (Files.exists(token)) {
      check(token.toFile().setWritable(true)) { "Cannot replace prior JMH token: $token" }
      Files.delete(token)
    }

    val selectedIncludes = includes.get()
    val jmhArguments = selectedIncludes.toMutableList()
    if (selectedIncludes.none { it.contains("HarnessFailureFixtureBenchmark") }) {
      jmhArguments += listOf("-e", "HarnessFailureFixtureBenchmark")
    }
    jmhArguments +=
      listOf("-rf", "json", "-rff", rawTemporary.toString(), "-o", human.toString())
    if (quick.get()) {
      jmhArguments += listOf("-wi", "0", "-i", "1", "-r", "100ms")
      if (!forks.isPresent) jmhArguments += listOf("-f", "1")
    }
    forks.orNull?.let { forkCount -> jmhArguments += listOf("-f", forkCount) }
    profilers.orNull
      ?.split(',')
      ?.filter(String::isNotBlank)
      ?.forEach { profiler -> jmhArguments += listOf("-prof", profiler) }
    setArgs(jmhArguments)
    systemProperties(
      mapOf(
        "revoman.benchmark.rawJmhOutput" to rawTemporary.toString(),
        "revoman.benchmark.resultOutput" to normalized.toString(),
        "revoman.benchmark.targetManifest" to manifest.toString(),
        "revoman.benchmark.adapter" to adapterId.get(),
        "revoman.benchmark.fixtureRoot" to fixtureRoot.toString(),
        "revoman.benchmark.includes" to selectedIncludes.joinToString("\u001f"),
        "revoman.benchmark.installationRoot" to root.toString(),
        "revoman.benchmark.requestedForks" to (forks.orNull ?: "1"),
        "revoman.benchmark.profilers" to profilers.orNull.orEmpty(),
        "revoman.benchmark.quick" to quick.get().toString(),
        "log4j2.configurationFile" to logging.toUri().toString(),
        "log4j2.*.Configuration.file" to logging.toUri().toString(),
        "kotlin-logging.logStartupMessage" to "false",
        "revoman.banner" to "off",
      )
    )

    super.exec()
    check(Files.isRegularFile(rawTemporary) && Files.size(rawTemporary) > 2) {
      "JMH did not produce non-empty JSON: $rawTemporary"
    }
    Files.move(rawTemporary, raw, ATOMIC_MOVE, REPLACE_EXISTING)
  }
}

abstract class BenchmarkHarnessSelfTest @Inject constructor(
  private val execOperations: ExecOperations
) : DefaultTask() {
  @get:Internal abstract val repositoryRoot: DirectoryProperty

  @get:InputFile abstract val wrapper: RegularFileProperty

  @get:InputFile abstract val sourceInitScript: RegularFileProperty

  @get:InputDirectory abstract val installationRoot: DirectoryProperty

  @get:Classpath abstract val installedClasspath: ConfigurableFileCollection

  @get:Input abstract val adapterId: Property<String>

  @get:OutputFile abstract val exportedManifest: RegularFileProperty

  @TaskAction
  fun verifyHarness() {
    val repository = repositoryRoot.get().asFile
    val manifest = exportedManifest.get().asFile
    Files.createDirectories(requireNotNull(manifest.toPath().parent))
    Files.deleteIfExists(manifest.toPath())
    execOperations
      .exec {
        workingDir(repository)
        commandLine(
          wrapper.get().asFile.absolutePath,
          "-I",
          sourceInitScript.get().asFile.absolutePath,
          "writeBenchmarkTargetManifest",
          "-Pbenchmark.targetManifest=${manifest.absolutePath}",
          "-Pbenchmark.targetId=self-test-current",
        )
      }
      .assertNormalExitValue()
    execOperations
      .javaexec {
        classpath(installedClasspath)
        mainClass.set("com.salesforce.revoman.benchmark.driver.jmh.HarnessSelfTestMainKt")
        args(
          installationRoot.get().asFile.absolutePath,
          manifest.absolutePath,
          adapterId.get(),
        )
      }
      .assertNormalExitValue()
  }
}

plugins {
  id("revoman.kt-conventions")
  application
  alias(libs.plugins.moshix)
  alias(libs.plugins.jmh)
}

dependencies {
  implementation(libs.moshix.adapters)
  implementation(libs.jmh.core)
  implementation(libs.commons.math3)
  implementation(libs.json.schema.validator)
  runtimeOnly(libs.log4j.api)
  runtimeOnly(libs.log4j.core)
  runtimeOnly(libs.log4j.slf4j2.impl)
  testImplementation(libs.truth)
  testImplementation(libs.mockk)
}

application { mainClass.set("com.salesforce.revoman.benchmark.driver.BenchmarkDriverMainKt") }

testing {
  suites {
    getByName<JvmTestSuite>("test") { useJUnitJupiter(libs.versions.junit.get()) }
    register<JvmTestSuite>("integrationTest") {
      useJUnitJupiter(libs.versions.junit.get())
      dependencies {
        implementation(project())
        libs.bundles.kotest.get().forEach { implementation(it) }
        implementation(libs.truth)
        implementation(libs.mockk)
      }
    }
  }
}

jmh {
  jmhVersion = libs.versions.jmh.get()
  includeTests = false
}

kotlin.target.compilations.named("integrationTest") {
  associateWith(kotlin.target.compilations.getByName("main"))
}

kotlin.target.compilations.named("jmh") {
  associateWith(kotlin.target.compilations.getByName("main"))
}

val benchmarkTargetManifest = providers.gradleProperty("benchmark.targetManifest")
val benchmarkAdapter = providers.gradleProperty("benchmark.adapter")

tasks.withType<Test>().configureEach {
  benchmarkTargetManifest.orNull?.let {
    systemProperty("revoman.benchmark.targetManifest", it)
  }
  benchmarkAdapter.orNull?.let {
    systemProperty("revoman.benchmark.adapter", it)
  }
}

val generatedClasses = layout.buildDirectory.dir("jmh-generated-classes")
val generatedResources = layout.buildDirectory.dir("jmh-generated-resources")

val benchmarkJmhClassesJar = tasks.register<Jar>("benchmarkJmhClassesJar") {
  group = "benchmark"
  description = "Packages only JMH benchmarks and generated JMH metadata"
  dependsOn("jmhCompileGeneratedClasses")
  archiveClassifier.set("jmh-classes")
  from(sourceSets["jmh"].output)
  from(generatedClasses)
  from(generatedResources)
}

val harnessSourceManifest =
  layout.buildDirectory.file("generated/benchmark-identity/benchmark-harness-source-v1.json")

val generateBenchmarkHarnessSource = tasks.register<JavaExec>("generateBenchmarkHarnessSource") {
  group = "benchmark"
  description = "Generates the non-self-referential benchmark harness source identity"
  dependsOn(tasks.named("classes"))
  mainClass.set("com.salesforce.revoman.benchmark.driver.integrity.BuildIdentityKt")
  classpath(sourceSets["main"].runtimeClasspath)
  inputs.files(
    fileTree("src/main/kotlin"),
    fileTree("src/jmh"),
    fileTree("src/main/resources"),
    fileTree("src/main/dist"),
  )
  outputs.file(harnessSourceManifest)
  args(rootProject.projectDir.canonicalPath, harnessSourceManifest.get().asFile.absolutePath)
}

distributions.named("main") {
  contents {
    from(benchmarkJmhClassesJar) {
      into("lib")
      rename { "benchmark-driver-jmh-classes.jar" }
    }
    from(generateBenchmarkHarnessSource) {
      into("conf")
      rename { "benchmark-harness-source-v1.json" }
    }
    from("src/main/dist")
    from("src/main/resources/schema") { into("schema") }
    from("src/main/resources/workloads") { into("workloads") }
    from("src/main/resources/jfr") { into("jfr") }
  }
}

tasks.named("installDist") {
  dependsOn(benchmarkJmhClassesJar, generateBenchmarkHarnessSource)
}

tasks.named("jmh") {
  onlyIf {
    logger.lifecycle(":benchmark-driver:jmh is disabled; use :benchmark-driver:benchmarkJmh")
    false
  }
}

tasks.named("jmhJar") {
  onlyIf {
    logger.lifecycle(":benchmark-driver:jmhJar is disabled; use benchmarkJmhClassesJar")
    false
  }
}

val benchmarkIncludes = providers.gradleProperty("benchmark.includes")
val benchmarkQuick = providers.gradleProperty("benchmark.quick").map(String::toBoolean).orElse(false)
val benchmarkForks = providers.gradleProperty("benchmark.forks")
val benchmarkProfilers = providers.gradleProperty("benchmark.profilers")
val benchmarkRawJmhOutput = providers.gradleProperty("benchmark.rawJmhOutput")
val benchmarkResultOutput = providers.gradleProperty("benchmark.resultOutput")
val benchmarkLogConfig = providers.gradleProperty("benchmark.logConfig")

val installedRoot = layout.buildDirectory.dir("install/benchmark-driver")
val installedLib = installedRoot.map { it.dir("lib") }
val rawJmhOutput =
  benchmarkRawJmhOutput
    .map(rootProject.layout.projectDirectory::file)
    .orElse(layout.buildDirectory.file("results/jmh/jmh-raw.json"))
val normalizedJmhOutput =
  benchmarkResultOutput
    .map(rootProject.layout.projectDirectory::file)
    .orElse(layout.buildDirectory.file("results/jmh/revoman-benchmark-jmh-v1.json"))
val humanJmhOutput = layout.file(rawJmhOutput.map { it.asFile.resolveSibling("jmh-output.txt") })
val resolvedLogConfig =
  benchmarkLogConfig
    .map(rootProject.layout.projectDirectory::file)
    .orElse(layout.projectDirectory.file("src/main/dist/conf/log4j2-benchmark.xml"))

val benchmarkJmh = tasks.register<StrictJmhJavaExec>("benchmarkJmh") {
  group = "benchmark"
  description = "Runs strict JMH from the installed original-JAR classpath"
  dependsOn("installDist")
  mainClass.set("com.salesforce.revoman.benchmark.driver.jmh.JmhDriverMainKt")
  classpath(
    providers.provider {
      installedLib.get().asFile.listFiles { file -> file.extension == "jar" }!!
        .sortedBy { it.name }
    }
  )
  installationRoot.set(installedRoot)
  targetManifest.set(benchmarkTargetManifest.map(rootProject.layout.projectDirectory::file))
  logConfig.set(resolvedLogConfig)
  logConfigOverride.set(benchmarkLogConfig)
  adapterId.set(benchmarkAdapter)
  includes.set(benchmarkIncludes.map(::listOf).orElse(listOf(".*")))
  quick.set(benchmarkQuick)
  forks.set(benchmarkForks)
  profilers.set(benchmarkProfilers)
  rawOutput.set(rawJmhOutput)
  normalizedOutput.set(normalizedJmhOutput)
  humanOutput.set(humanJmhOutput)
}

tasks.register<BenchmarkHarnessSelfTest>("benchmarkHarnessSelfTest") {
  group = "verification"
  description = "Verifies the original-JAR JMH classpath and fatal failure behavior"
  dependsOn("installDist")
  repositoryRoot.set(rootProject.layout.projectDirectory)
  wrapper.set(rootProject.layout.projectDirectory.file("gradlew"))
  sourceInitScript.set(
    layout.projectDirectory.file("src/main/dist/libexec/benchmark-target.init.gradle.kts")
  )
  installationRoot.set(installedRoot)
  installedClasspath.from(
    providers.provider {
      installedLib.get().asFile.listFiles { file -> file.extension == "jar" }!!
        .sortedBy { it.name }
    }
  )
  adapterId.set(benchmarkAdapter)
  exportedManifest.set(layout.buildDirectory.file("self-test/benchmark-target-current.json"))
  outputs.upToDateWhen { false }
}
