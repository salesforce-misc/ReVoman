package com.salesforce.revoman.benchmark.reporting

import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class JavaIdentity(
  val feature: Int,
  val identity: String,
)

internal data class RuntimeValidation(
  val revision: String,
  val timestamp: String,
  val methods: List<String>,
  val debuggerTool: String,
  val debuggerSession: String,
  val assertions: Map<String, Boolean>,
)

internal data class ScorecardPreflight(
  val projectRoot: Path,
  val benchmarkJar: Path,
  val javaExecutable: Path,
  val libraryVersion: String,
  val revision: String,
  val allowedDirtyPaths: Set<Path>,
  val javaIdentities: Map<String, JavaIdentity>,
  val runtimeValidation: RuntimeValidation,
)

internal class ScorecardPreflightValidator(private val host: ScorecardHost) {
  fun validate(request: ScorecardRunRequest): ScorecardPreflight {
    val paths = resolvedPaths(request)
    validateInputs(request, paths)
    val java = validateJavaLayers(paths)
    val revision =
      successfulOutput(listOf("git", "rev-parse", "HEAD"), paths.projectRoot, "Git revision")
    require(revision.matches(Regex("[0-9a-fA-F]{40}"))) { "Git revision must be a full SHA-1" }
    val validation = readRuntimeValidation(request.runtimeValidation.toAbsolutePath().normalize())
    validateRuntimeRecord(validation, revision)
    val allowedDirtyPaths = approvedDirtyPaths(paths.projectRoot, request.allowedDirtyPaths)

    return ScorecardPreflight(
      paths.projectRoot,
      paths.benchmarkJar,
      paths.javaExecutable,
      request.libraryVersion,
      revision,
      allowedDirtyPaths,
      linkedMapOf(
        "runner" to JavaIdentity(host.runnerJavaFeature, host.runnerJavaIdentity),
        "launcher" to java.launcher,
        "inherited" to java.inherited,
        "gradleDaemon" to
          JavaIdentity(
            request.gradleDaemonJavaFeature,
            "Gradle daemon Java ${request.gradleDaemonJavaFeature}",
          ),
        "jmh" to JavaIdentity(request.javaFeature, java.launcher.identity),
      ),
      validation,
    )
  }

  private fun resolvedPaths(request: ScorecardRunRequest): PreflightPaths =
    PreflightPaths(
      request.projectRoot.toAbsolutePath().normalize(),
      request.benchmarkJar.toAbsolutePath().normalize(),
      request.javaExecutable.toAbsolutePath().normalize(),
    )

  private fun validateInputs(request: ScorecardRunRequest, paths: PreflightPaths) {
    val fingerprintInputs =
      listOf(
        paths.projectRoot.resolve("gradle/libs.versions.toml"),
        paths.projectRoot.resolve("gradle/wrapper/gradle-wrapper.properties"),
      )
    require(fingerprintInputs.all { host.isRegularFile(it) && host.isReadable(it) }) {
      "Dependency fingerprint inputs must be readable regular files"
    }
    require(paths.benchmarkJar.startsWith(paths.projectRoot)) {
      "Benchmark JAR must be inside the project root for a relative dependency fingerprint"
    }
    require(host.runnerJavaFeature == EXPECTED_JAVA_FEATURE) {
      "runner Java feature must be 25, got ${host.runnerJavaFeature}"
    }
    require(host.runnerJavaIdentity.isNotBlank()) { "runner Java identity must not be blank" }
    require(request.javaFeature == EXPECTED_JAVA_FEATURE) {
      "JMH Java feature must be 25, got ${request.javaFeature}"
    }
    require(request.gradleDaemonJavaFeature == EXPECTED_JAVA_FEATURE) {
      "Gradle daemon Java feature must be 25, got ${request.gradleDaemonJavaFeature}"
    }
    require(request.gradleMaxWorkers == 1) { "Scorecard requires exactly one worker" }
    require(request.libraryVersion.isNotBlank()) { "Library version must not be blank" }
    require(host.isRegularFile(paths.javaExecutable) && host.isReadable(paths.javaExecutable)) {
      "Selected Java launcher must be a readable regular file"
    }
    require(host.isExecutable(paths.javaExecutable)) { "Selected Java launcher must be executable" }
    require(host.isRegularFile(paths.benchmarkJar) && host.isReadable(paths.benchmarkJar)) {
      "Benchmark JAR must be a readable regular file"
    }
    require(isJmhJar(paths.benchmarkJar)) { "Benchmark JAR is not an executable JMH JAR" }
  }

  private fun validateJavaLayers(paths: PreflightPaths): ValidatedJava {
    val launcher = probeJava(paths.javaExecutable, paths.projectRoot, "launcher")
    require(launcher.feature == EXPECTED_JAVA_FEATURE) {
      "launcher Java feature must be 25, got ${launcher.feature}"
    }
    val inheritedExecutable = inheritedJavaExecutable()
    require(host.isRegularFile(inheritedExecutable) && host.isExecutable(inheritedExecutable)) {
      "Inherited JAVA_HOME/PATH Java must be executable"
    }
    val inherited = probeJava(inheritedExecutable, paths.projectRoot, "inherited")
    require(inherited.feature == EXPECTED_JAVA_FEATURE) {
      "inherited Java feature must be 25, got ${inherited.feature}"
    }
    return ValidatedJava(launcher, inherited)
  }

  private fun validateRuntimeRecord(validation: RuntimeValidation, revision: String) {
    require(validation.revision == revision) {
      "Runtime-validation revision does not match Git revision"
    }
    require(runCatching { Instant.parse(validation.timestamp) }.isSuccess) {
      "Runtime-validation timestamp must be a UTC instant"
    }
    require(
      validation.methods == expectedScorecardRows.map { it.benchmark.substringAfterLast('.') }
    ) {
      "Runtime validation must name all seven scorecard methods in order"
    }
    require(validation.assertions.keys == REQUIRED_DEBUGGER_ASSERTIONS) {
      "Runtime validation must name all approved debugger assertions"
    }
    require(validation.assertions.values.all { it }) {
      "Every approved debugger assertion must be true"
    }
    require(validation.debuggerTool.isNotBlank() && validation.debuggerSession.isNotBlank()) {
      "Runtime validation must identify the debugger tool and session"
    }
  }

  private fun approvedDirtyPaths(projectRoot: Path, requestedPaths: Set<Path>): Set<Path> {
    val allowedDirtyPaths = normalizeAllowedDirtyPaths(projectRoot, requestedPaths)
    val dirtyOutput =
      successfulOutput(
        listOf("git", "status", "--porcelain=v1", "-z"),
        projectRoot,
        "Git status",
        trim = false,
      )
    val dirtyPaths = parsePorcelainPaths(projectRoot, dirtyOutput)
    val unexpected = dirtyPaths - allowedDirtyPaths
    require(unexpected.isEmpty()) {
      "Repository has unapproved dirty paths: ${unexpected.joinToString { projectRoot.relativize(it).toString() }}"
    }
    return allowedDirtyPaths
  }

  private fun inheritedJavaExecutable(): Path =
    host.environmentVariable("JAVA_HOME")?.takeIf(String::isNotBlank)?.let {
      Path.of(it).resolve("bin/java").toAbsolutePath().normalize()
    }
      ?: host
        .environmentVariable("PATH")
        .orEmpty()
        .split(System.getProperty("path.separator"))
        .asSequence()
        .filter(String::isNotBlank)
        .map { Path.of(it).resolve("java").toAbsolutePath().normalize() }
        .firstOrNull { host.isRegularFile(it) && host.isExecutable(it) }
      ?: error("Inherited JAVA_HOME/PATH does not select an executable Java")

  private fun probeJava(executable: Path, projectRoot: Path, layer: String): JavaIdentity {
    val result =
      host.executeReadOnly(
        listOf(executable.toString(), "-XshowSettings:properties", "-version"),
        projectRoot,
      )
    require(result.exitCode == 0) { "$layer Java metadata probe failed: ${result.stderr.trim()}" }
    val output = result.stdout + "\n" + result.stderr
    val properties =
      output
        .lineSequence()
        .map(String::trim)
        .mapNotNull { line ->
          line
            .substringBefore(" = ")
            .takeIf { line.contains(" = ") }
            ?.let {
              it to line.substringAfter(" = ")
            }
        }
        .toMap()
    val version =
      properties["java.version"] ?: output.lineSequence().firstOrNull { "version \"" in it }
    val feature =
      version
        ?.substringAfter("version \"")
        ?.substringBefore('.')
        ?.substringBefore('"')
        ?.toIntOrNull()
        ?: version?.substringBefore('.')?.substringBefore('-')?.toIntOrNull()
        ?: error("$layer Java metadata did not include java.version")
    val identity =
      listOf("java.runtime.name", "java.version", "java.vm.name", "java.vendor")
        .mapNotNull(properties::get)
        .filter(String::isNotBlank)
        .joinToString("; ")
    require(identity.isNotBlank()) { "$layer Java metadata did not include an identity" }
    return JavaIdentity(feature, identity)
  }

  private fun successfulOutput(
    command: List<String>,
    projectRoot: Path,
    description: String,
    trim: Boolean = true,
  ): String {
    val result = host.executeReadOnly(command, projectRoot)
    require(result.exitCode == 0) { "$description failed: ${result.stderr.trim()}" }
    return if (trim) result.stdout.trim() else result.stdout
  }

  private fun isJmhJar(path: Path): Boolean =
    runCatching {
        ZipFile(path.toFile()).use { zip ->
          zip.getEntry("META-INF/BenchmarkList") != null &&
            zip.getEntry("org/openjdk/jmh/Main.class") != null
        }
      }
      .getOrDefault(false)

  private fun readRuntimeValidation(path: Path): RuntimeValidation {
    require(host.isRegularFile(path) && host.isReadable(path)) {
      "Runtime-validation record must be readable"
    }
    val json = Json.parseToJsonElement(host.readText(path)).jsonObject
    val assertions =
      json.getValue("assertions").jsonObject.mapValues { (_, value) ->
        requireNotNull(value.jsonPrimitive.booleanOrNull) {
          "Runtime-validation assertions must be boolean"
        }
      }
    val debugger = json.getValue("debugger").jsonObject
    return RuntimeValidation(
      revision = json.getValue("revision").jsonPrimitive.content,
      timestamp = json.getValue("timestamp").jsonPrimitive.content,
      methods = json.getValue("methods").jsonArray.map { it.jsonPrimitive.content },
      debuggerTool = debugger.getValue("tool").jsonPrimitive.content,
      debuggerSession = debugger.getValue("session").jsonPrimitive.content,
      assertions = assertions,
    )
  }
}

private data class PreflightPaths(
  val projectRoot: Path,
  val benchmarkJar: Path,
  val javaExecutable: Path,
)

private data class ValidatedJava(
  val launcher: JavaIdentity,
  val inherited: JavaIdentity,
)

private val REQUIRED_DEBUGGER_ASSERTIONS =
  setOf(
    "v2LoaderPath",
    "v3LoaderPaths",
    "scriptIsolation",
    "handlerInvocationCounts",
    "environmentHandoffs",
    "runbookContracts",
    "verbosePreparedRundown",
  )

private fun normalizeAllowedDirtyPaths(projectRoot: Path, paths: Set<Path>): Set<Path> =
  paths.mapTo(linkedSetOf()) { path ->
    val absolute =
      (if (path.isAbsolute) path else projectRoot.resolve(path)).toAbsolutePath().normalize()
    require(absolute.startsWith(projectRoot) && absolute != projectRoot) {
      "Allowed dirty path must be inside the project root"
    }
    absolute
  }

private fun parsePorcelainPaths(projectRoot: Path, output: String): Set<Path> =
  parsePorcelainRecords(
    projectRoot,
    output.split('\u0000').filter(String::isNotEmpty),
    index = 0,
    paths = emptySet(),
  )

private tailrec fun parsePorcelainRecords(
  projectRoot: Path,
  records: List<String>,
  index: Int,
  paths: Set<Path>,
): Set<Path> {
  if (index >= records.size) return paths
  val record = records[index]
  require(record.length >= PORCELAIN_RECORD_PREFIX_LENGTH + 1 && record[2] == ' ') {
    "Malformed Git status record"
  }
  val status = record.substring(0, 2)
  val destination = normalizeGitPath(projectRoot, record.substring(3))
  val hasSourcePath = status.any { it == 'R' || it == 'C' }
  val source =
    if (hasSourcePath) {
      require(index + 1 < records.size) { "Malformed Git rename or copy record" }
      normalizeGitPath(projectRoot, records[index + 1])
    } else {
      null
    }
  val updatedPaths = paths + setOf(destination) + (source?.let(::setOf) ?: emptySet())
  return parsePorcelainRecords(
    projectRoot,
    records,
    index + if (hasSourcePath) 2 else 1,
    updatedPaths,
  )
}

private fun normalizeGitPath(projectRoot: Path, value: String): Path =
  projectRoot.resolve(value).toAbsolutePath().normalize().also {
    require(it.startsWith(projectRoot) && it != projectRoot) {
      "Git status contains a path outside the project root"
    }
  }

private const val PORCELAIN_RECORD_PREFIX_LENGTH = 3
