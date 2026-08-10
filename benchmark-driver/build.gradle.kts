import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale
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
    val selectedIncludes = includes.get()
    val lifecycleAllocation =
      selectedIncludes.isNotEmpty() &&
        selectedIncludes.all { it.contains("WarmLifecycleAllocationBenchmark") }
    val fixtureRoot =
      root
        .resolve(
          if (lifecycleAllocation) "workloads/v1/lifecycle.no-script-one-step.v1"
          else "workloads/v1/jmh.component-operations.v1"
        )
        .toRealPath()
    val raw = rawOutput.get().asFile.toPath().toAbsolutePath().normalize()
    val normalized = normalizedOutput.get().asFile.toPath().toAbsolutePath().normalize()
    val human = humanOutput.get().asFile.toPath().toAbsolutePath().normalize()
    val rawTemporary = raw.resolveSibling(".${raw.fileName}.tmp")
    val token = rawTemporary.resolveSibling("${rawTemporary.fileName}.target-token.json")
    requirePairwiseDistinctOutputs(
      listOf(
        "raw" to raw,
        "normalized" to normalized,
        "human" to human,
        "raw temporary" to rawTemporary,
        "target token" to token,
      )
    )
    listOf(raw, rawTemporary, normalized, human).forEach { output ->
      Files.createDirectories(requireNotNull(output.parent))
      Files.deleteIfExists(output)
    }
    if (Files.exists(token)) {
      check(token.toFile().setWritable(true)) { "Cannot replace prior JMH token: $token" }
      Files.delete(token)
    }

    val jmhArguments = selectedIncludes.toMutableList()
    if (selectedIncludes.none { it.contains("HarnessFailureFixtureBenchmark") }) {
      jmhArguments += listOf("-e", "HarnessFailureFixtureBenchmark")
    }
    if (selectedIncludes.none { it.contains("WarmLifecycleAllocationBenchmark") }) {
      jmhArguments += listOf("-e", "WarmLifecycleAllocationBenchmark")
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

  private fun requirePairwiseDistinctOutputs(outputs: List<Pair<String, Path>>) {
    val comparable = outputs.map { (label, path) -> Triple(label, path, canonicalOutputPath(path)) }
    comparable.forEachIndexed { index, first ->
      comparable.drop(index + 1).forEach { second ->
        val aliases =
          first.third == second.third ||
            (Files.exists(first.second) &&
              Files.exists(second.second) &&
              Files.isSameFile(first.second, second.second))
        require(!aliases) {
          "JMH output paths must be pairwise distinct: " +
            "${first.first}=${first.second} aliases ${second.first}=${second.second}"
        }
      }
    }
  }

  private fun canonicalOutputPath(path: Path): String {
    var existing = path
    val nonexistentSuffix = mutableListOf<Path>()
    while (!Files.exists(existing)) {
      nonexistentSuffix.add(
        requireNotNull(existing.fileName) { "JMH output path has no existing ancestor: $path" }
      )
      existing = requireNotNull(existing.parent) {
        "JMH output path has no existing ancestor: $path"
      }
    }
    val canonical =
      nonexistentSuffix
        .asReversed()
        .fold(existing.toRealPath()) { resolved, segment -> resolved.resolve(segment) }
        .normalize()
        .toString()
    return if (isCaseInsensitive(existing)) canonical.lowercase(Locale.ROOT) else canonical
  }

  private fun isCaseInsensitive(path: Path): Boolean {
    var existing: Path? = path.toRealPath()
    while (existing?.fileName != null) {
      val name = existing.fileName.toString()
      val alternateName =
        if (name.any(Char::isLowerCase)) name.uppercase(Locale.ROOT)
        else name.lowercase(Locale.ROOT)
      if (alternateName != name) {
        val alternate = existing.resolveSibling(alternateName)
        if (Files.exists(alternate) && Files.isSameFile(existing, alternate)) return true
      }
      existing = existing.parent
    }
    return false
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

abstract class BenchmarkJmhFreshnessTest @Inject constructor(
  private val execOperations: ExecOperations
) : DefaultTask() {
  @get:Internal abstract val repositoryRoot: DirectoryProperty

  @get:InputFile abstract val wrapper: RegularFileProperty

  @get:InputFile abstract val targetManifest: RegularFileProperty

  @get:Input abstract val adapterId: Property<String>

  @get:Internal abstract val outputRoot: DirectoryProperty

  @TaskAction
  fun verifyFreshExecution() {
    val output = outputRoot.get().asFile.toPath().toAbsolutePath().normalize()
    val raw = output.resolve("jmh-raw.json")
    val normalized = output.resolve("revoman-benchmark-jmh-v1.json")
    val human = output.resolve("jmh-output.txt")
    Files.createDirectories(output)
    listOf(raw, normalized, human).forEach(Files::deleteIfExists)
    val arguments =
      benchmarkArguments(
        raw = raw,
        normalized = normalized,
        targetManifest = targetManifest.get().asFile.toPath(),
        adapterId = adapterId.get(),
      )

    val first = runGradle(arguments)
    check(first.exitCode == 0) { "First identical JMH command failed: ${first.output}" }
    val firstRaw = Files.readAllBytes(raw)
    val firstNormalized = Files.readAllBytes(normalized)
    val second = runGradle(arguments)
    check(second.exitCode == 0) { "Second identical JMH command failed: ${second.output}" }
    check(!second.output.lineSequence().any { it.contains(":benchmark-driver:benchmarkJmh UP-TO-DATE") }) {
      "Second identical JMH command reused stale evidence:\n${second.output}"
    }
    check(!second.output.lineSequence().any { it.contains(":benchmark-driver:benchmarkJmh FROM-CACHE") }) {
      "Second identical JMH command restored cached evidence:\n${second.output}"
    }
    check(!Files.readAllBytes(raw).contentEquals(firstRaw)) {
      "Second identical JMH command did not replace raw evidence"
    }
    check(!Files.readAllBytes(normalized).contentEquals(firstNormalized)) {
      "Second identical JMH command did not replace normalized evidence"
    }
  }

  private fun runGradle(arguments: List<String>): GradleInvocation {
    val output = ByteArrayOutputStream()
    val result =
      execOperations.exec {
        workingDir(repositoryRoot.get().asFile)
        commandLine(wrapper.get().asFile.absolutePath, *arguments.toTypedArray())
        standardOutput = output
        errorOutput = output
        isIgnoreExitValue = true
      }
    return GradleInvocation(result.exitValue, output.toString(Charsets.UTF_8))
  }

  private fun benchmarkArguments(
    raw: Path,
    normalized: Path,
    targetManifest: Path,
    adapterId: String,
  ): List<String> =
    listOf(
      ":benchmark-driver:benchmarkJmh",
      "-Pbenchmark.includes=HarnessSanityBenchmark",
      "-Pbenchmark.targetManifest=${targetManifest.toAbsolutePath().normalize()}",
      "-Pbenchmark.adapter=$adapterId",
      "-Pbenchmark.quick=true",
      "-Pbenchmark.rawJmhOutput=$raw",
      "-Pbenchmark.resultOutput=$normalized",
      "--console=plain",
    )

  private data class GradleInvocation(val exitCode: Int, val output: String)
}

abstract class BenchmarkJmhOutputCollisionTest @Inject constructor(
  private val execOperations: ExecOperations
) : DefaultTask() {
  @get:Internal abstract val repositoryRoot: DirectoryProperty

  @get:InputFile abstract val wrapper: RegularFileProperty

  @get:InputFile abstract val targetManifest: RegularFileProperty

  @get:Input abstract val adapterId: Property<String>

  @get:Internal abstract val outputRoot: DirectoryProperty

  @TaskAction
  fun verifyCollisionPreservesPriorEvidence() {
    val output = outputRoot.get().asFile.toPath().toAbsolutePath().normalize()
    val raw = output.resolve("jmh-raw.json")
    val normalizedAlias = output.resolve("revoman-benchmark-jmh-v1.json")
    val human = output.resolve("jmh-output.txt")
    Files.createDirectories(output)
    listOf(raw, normalizedAlias, human).forEach(Files::deleteIfExists)
    Files.writeString(raw, PRIOR_EVIDENCE)
    Files.createLink(normalizedAlias, raw)

    val captured = ByteArrayOutputStream()
    val result =
      execOperations.exec {
        workingDir(repositoryRoot.get().asFile)
        commandLine(
          wrapper.get().asFile.absolutePath,
          *benchmarkArguments(
              raw = raw,
              normalized = normalizedAlias,
              targetManifest = targetManifest.get().asFile.toPath(),
              adapterId = adapterId.get(),
            )
            .toTypedArray(),
        )
        standardOutput = captured
        errorOutput = captured
        isIgnoreExitValue = true
      }
    val processOutput = captured.toString(Charsets.UTF_8)
    check(result.exitValue != 0) { "Aliased JMH outputs unexpectedly succeeded:\n$processOutput" }
    check(processOutput.contains("JMH output paths must be pairwise distinct")) {
      "Aliased JMH outputs failed for the wrong reason:\n$processOutput"
    }
    check(processOutput.contains("raw=") && processOutput.contains("normalized=")) {
      "Aliased JMH output diagnostic did not identify both paths:\n$processOutput"
    }
    check(Files.isSameFile(raw, normalizedAlias)) {
      "Aliased JMH output failure replaced one of the prior files"
    }
    check(Files.readString(raw) == PRIOR_EVIDENCE) {
      "Aliased JMH output failure changed prior evidence"
    }
    check(!Files.exists(human) && !Files.exists(raw.resolveSibling(".${raw.fileName}.tmp"))) {
      "Aliased JMH output failure reached execution outputs"
    }

    Files.delete(normalizedAlias)
    Files.delete(raw)
    val realParent = output.resolve("real-parent")
    val aliasParent = output.resolve("alias-parent")
    Files.createDirectories(realParent)
    Files.deleteIfExists(aliasParent)
    Files.createSymbolicLink(aliasParent, realParent)
    val nonexistentRaw = realParent.resolve("shared.json")
    val nonexistentNormalizedAlias = aliasParent.resolve("shared.json")
    Files.deleteIfExists(nonexistentRaw)
    val nonexistentCaptured = ByteArrayOutputStream()
    val nonexistentResult =
      execOperations.exec {
        workingDir(repositoryRoot.get().asFile)
        commandLine(
          wrapper.get().asFile.absolutePath,
          *benchmarkArguments(
              raw = nonexistentRaw,
              normalized = nonexistentNormalizedAlias,
              targetManifest = targetManifest.get().asFile.toPath(),
              adapterId = adapterId.get(),
            )
            .toTypedArray(),
        )
        standardOutput = nonexistentCaptured
        errorOutput = nonexistentCaptured
        isIgnoreExitValue = true
      }
    val nonexistentOutput = nonexistentCaptured.toString(Charsets.UTF_8)
    check(nonexistentResult.exitValue != 0) {
      "Nonexistent outputs below aliased parents unexpectedly succeeded:\n$nonexistentOutput"
    }
    check(nonexistentOutput.contains("JMH output paths must be pairwise distinct")) {
      "Nonexistent outputs below aliased parents failed for the wrong reason:\n$nonexistentOutput"
    }
    check(!Files.exists(nonexistentRaw)) {
      "Nonexistent aliased JMH output reached execution"
    }

    val caseParent = output.resolve("case-parent")
    Files.createDirectories(caseParent)
    if (isCaseInsensitive(caseParent)) {
      val caseRaw = caseParent.resolve("evidence.json")
      val caseNormalized = caseParent.resolve("JMH-OUTPUT.TXT")
      val caseHuman = caseParent.resolve("jmh-output.txt")
      listOf(caseRaw, caseNormalized, caseHuman).forEach(Files::deleteIfExists)
      val caseCaptured = ByteArrayOutputStream()
      val caseResult =
        execOperations.exec {
          workingDir(repositoryRoot.get().asFile)
          commandLine(
            wrapper.get().asFile.absolutePath,
            *benchmarkArguments(
                raw = caseRaw,
                normalized = caseNormalized,
                targetManifest = targetManifest.get().asFile.toPath(),
                adapterId = adapterId.get(),
              )
              .toTypedArray(),
          )
          standardOutput = caseCaptured
          errorOutput = caseCaptured
          isIgnoreExitValue = true
        }
      val caseOutput = caseCaptured.toString(Charsets.UTF_8)
      check(caseResult.exitValue != 0) {
        "Case-variant aliased outputs unexpectedly succeeded:\n$caseOutput"
      }
      check(caseOutput.contains("JMH output paths must be pairwise distinct")) {
        "Case-variant aliased outputs failed for the wrong reason:\n$caseOutput"
      }
      check(!Files.exists(caseRaw) && !Files.exists(caseNormalized) && !Files.exists(caseHuman)) {
        "Case-variant aliased JMH output reached execution"
      }
    }
  }

  private companion object {
    const val PRIOR_EVIDENCE: String = "prior-evidence"
  }

  private fun isCaseInsensitive(directory: Path): Boolean {
    val lower = directory.resolve("case-sensitivity-probe")
    val upper = directory.resolve("CASE-SENSITIVITY-PROBE")
    Files.deleteIfExists(lower)
    Files.deleteIfExists(upper)
    Files.writeString(lower, "probe")
    return try {
      Files.exists(upper) && Files.isSameFile(lower, upper)
    } finally {
      Files.deleteIfExists(lower)
      Files.deleteIfExists(upper)
    }
  }

  private fun benchmarkArguments(
    raw: Path,
    normalized: Path,
    targetManifest: Path,
    adapterId: String,
  ): List<String> =
    listOf(
      ":benchmark-driver:benchmarkJmh",
      "-Pbenchmark.includes=HarnessSanityBenchmark",
      "-Pbenchmark.targetManifest=${targetManifest.toAbsolutePath().normalize()}",
      "-Pbenchmark.adapter=$adapterId",
      "-Pbenchmark.quick=true",
      "-Pbenchmark.rawJmhOutput=$raw",
      "-Pbenchmark.resultOutput=$normalized",
      "--console=plain",
    )
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

val harnessGitState = providers.provider {
  fun git(vararg arguments: String): String {
    val process =
      ProcessBuilder(listOf("git", "-C", rootProject.projectDir.canonicalPath) + arguments)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) { "Git identity command failed: $output" }
    return output.trimEnd()
  }
  listOf(
      git("rev-parse", "HEAD"),
      git("rev-parse", "HEAD^{tree}"),
      git("status", "--porcelain", "--untracked-files=normal"),
    )
    .joinToString("\u0000")
}

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
  inputs.property("gitState", harnessGitState)
  outputs.file(harnessSourceManifest)
  outputs.doNotCacheIf("Harness source identity records checkout-local Git state") { true }
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

tasks.named("integrationTest") { dependsOn("installDist") }

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
  outputs.upToDateWhen { false }
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

tasks.register<BenchmarkJmhFreshnessTest>("benchmarkJmhFreshnessTest") {
  group = "verification"
  description = "Runs an identical JMH command twice and requires fresh evidence"
  repositoryRoot.set(rootProject.layout.projectDirectory)
  wrapper.set(rootProject.layout.projectDirectory.file("gradlew"))
  targetManifest.set(benchmarkTargetManifest.map(rootProject.layout.projectDirectory::file))
  adapterId.set(benchmarkAdapter)
  outputRoot.set(layout.buildDirectory.dir("self-test/freshness"))
  outputs.upToDateWhen { false }
}

tasks.register<BenchmarkJmhOutputCollisionTest>("benchmarkJmhOutputCollisionTest") {
  group = "verification"
  description = "Rejects aliased JMH outputs without changing prior evidence"
  repositoryRoot.set(rootProject.layout.projectDirectory)
  wrapper.set(rootProject.layout.projectDirectory.file("gradlew"))
  targetManifest.set(benchmarkTargetManifest.map(rootProject.layout.projectDirectory::file))
  adapterId.set(benchmarkAdapter)
  outputRoot.set(layout.buildDirectory.dir("self-test/output-collision"))
  outputs.upToDateWhen { false }
}
